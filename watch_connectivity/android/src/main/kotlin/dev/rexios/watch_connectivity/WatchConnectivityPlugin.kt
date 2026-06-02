package dev.rexios.watch_connectivity

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.google.android.gms.wearable.*
import com.google.android.gms.wearable.DataEvent.TYPE_CHANGED
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream


/** WatchConnectivityPlugin */
class WatchConnectivityPlugin : FlutterPlugin, MethodCallHandler,
    MessageClient.OnMessageReceivedListener, DataClient.OnDataChangedListener {
    companion object {
        private const val channelName = "watch_connectivity"
        private const val tag = "WatchConnectivity"
        private const val wearableUnavailableCode = "wearable_runtime_unavailable"
        private val companionPackages = setOf(
            "com.google.android.wearable.app",
            "com.samsung.android.app.watchmanager",
        )
    }

    /// The MethodChannel that will the communication between Flutter and native Android
    ///
    /// This local reference serves to register the plugin with the Flutter Engine and unregister it
    /// when the Flutter Engine is detached from the Activity
    private lateinit var channel: MethodChannel
    private lateinit var applicationContext: Context
    private lateinit var packageManager: PackageManager
    private var nodeClient: NodeClient? = null
    private var messageClient: MessageClient? = null
    private var dataClient: DataClient? = null
    private var localNodeId: String? = null
    private var listenersRegistered = false

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, channelName)
        channel.setMethodCallHandler(this)

        applicationContext = flutterPluginBinding.applicationContext
        packageManager = applicationContext.packageManager
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        clearWearableClients()
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            // Getters
            "isSupported" -> result.success(true)
            "isPaired" -> isPaired(result)
            "isReachable" -> isReachable(result)
            "applicationContext" -> applicationContext(result)
            "receivedApplicationContexts" -> receivedApplicationContexts(result)

            // Methods
            "sendMessage" -> sendMessage(call, result)
            "updateApplicationContext" -> updateApplicationContext(call, result)

            // Not implemented
            else -> result.notImplemented()
        }
    }

    private fun objectToBytes(`object`: Any): ByteArray {
        val baos = ByteArrayOutputStream()
        val oos = ObjectOutputStream(baos)
        oos.writeObject(`object`)
        return baos.toByteArray()
    }

    private fun objectFromBytes(bytes: ByteArray): Any {
        val bis = ByteArrayInputStream(bytes)
        val ois = ObjectInputStream(bis)
        return ois.readObject()
    }

    private fun hasCompanionAppInstalled(): Boolean {
        return runCatching {
            val apps = packageManager.getInstalledApplications(0)
            apps.any { companionPackages.contains(it.packageName) }
        }.getOrElse {
            Log.w(tag, "Failed to inspect installed watch apps", it)
            false
        }
    }

    private fun ensureWearableClients(): Boolean {
        if (nodeClient != null && messageClient != null && dataClient != null) {
            registerListenersIfNeeded()
            return true
        }

        if (!hasCompanionAppInstalled()) {
            return false
        }

        return runCatching {
            nodeClient = Wearable.getNodeClient(applicationContext)
            messageClient = Wearable.getMessageClient(applicationContext)
            dataClient = Wearable.getDataClient(applicationContext)
            registerListenersIfNeeded()
            true
        }.getOrElse {
            Log.w(tag, "Wearable client init failed", it)
            clearWearableClients()
            false
        }
    }

    private fun registerListenersIfNeeded() {
        if (listenersRegistered) {
            return
        }

        val localMessageClient = messageClient ?: return
        val localDataClient = dataClient ?: return
        localMessageClient.addListener(this)
        localDataClient.addListener(this)
        listenersRegistered = true
    }

    private fun clearWearableClients() {
        if (listenersRegistered) {
            messageClient?.removeListener(this)
            dataClient?.removeListener(this)
            listenersRegistered = false
        }
        nodeClient = null
        messageClient = null
        dataClient = null
        localNodeId = null
    }

    private fun withRequiredLocalNodeId(result: Result, onReady: (String) -> Unit) {
        localNodeId?.let {
            onReady(it)
            return
        }

        val client = nodeClient
        if (client == null) {
            result.error(wearableUnavailableCode, "Wear OS runtime unavailable", null)
            return
        }

        client.localNode
            .addOnSuccessListener { node ->
                localNodeId = node.id
                onReady(node.id)
            }
            .addOnFailureListener {
                Log.w(tag, "Failed to resolve local Wear OS node", it)
                result.error(it.javaClass.simpleName, it.message ?: "Failed to resolve local Wear OS node", it)
            }
    }

    private fun isPaired(result: Result) {
        result.success(hasCompanionAppInstalled())
    }

    private fun isReachable(result: Result) {
        if (!hasCompanionAppInstalled()) {
            result.success(false)
            return
        }

        if (!ensureWearableClients()) {
            result.error(wearableUnavailableCode, "Wear OS runtime unavailable", null)
            return
        }

        val client = nodeClient
        if (client == null) {
            result.error(wearableUnavailableCode, "Wear OS runtime unavailable", null)
            return
        }

        client.connectedNodes
            .addOnSuccessListener { result.success(it.isNotEmpty()) }
            .addOnFailureListener { result.error(it.javaClass.simpleName, it.localizedMessage, it) }
    }
    
    @SuppressLint("VisibleForTests")
    private fun applicationContext(result: Result) {
        if (!hasCompanionAppInstalled()) {
            result.success(emptyMap<String, Any>())
            return
        }

        if (!ensureWearableClients()) {
            result.error(wearableUnavailableCode, "Wear OS runtime unavailable", null)
            return
        }

        val client = dataClient
        if (client == null) {
            result.error(wearableUnavailableCode, "Wear OS runtime unavailable", null)
            return
        }

        withRequiredLocalNodeId(result) { localId ->
            client.dataItems
                .addOnSuccessListener { items ->
                    val localNodeItem = items.firstOrNull {
                        it.uri.host == localId && it.uri.path == "/$channelName"
                    }
                    if (localNodeItem != null) {
                        try {
                            val itemContent = objectFromBytes(localNodeItem.data!!)
                            result.success(itemContent)
                        } catch (e: Exception) {
                            result.error(e.javaClass.simpleName, e.message ?: "Erreur lors de la désérialisation", e)
                        }
                    } else {
                        result.success(emptyMap<String, Any>())
                    }
                }
                .addOnFailureListener { exception ->
                    result.error(exception.javaClass.simpleName, exception.message ?: "Erreur lors de la récupération des dataItems", exception)
                }
        }
    }

    @SuppressLint("VisibleForTests")
    private fun receivedApplicationContexts(result: Result) {
        if (!hasCompanionAppInstalled()) {
            result.success(emptyList<Any>())
            return
        }

        if (!ensureWearableClients()) {
            result.error(wearableUnavailableCode, "Wear OS runtime unavailable", null)
            return
        }

        val client = dataClient
        if (client == null) {
            result.error(wearableUnavailableCode, "Wear OS runtime unavailable", null)
            return
        }

        withRequiredLocalNodeId(result) { localId ->
            client.dataItems
                .addOnSuccessListener { items ->
                    val itemContents = items.filter {
                        it.uri.host != localId && it.uri.path == "/$channelName"
                    }.map { objectFromBytes(it.data!!) }
                    result.success(itemContents)
                }
                .addOnFailureListener {
                    result.error(it.javaClass.simpleName, it.localizedMessage, it)
                }
        }
    }

    private fun sendMessage(call: MethodCall, result: Result) {
        if (!hasCompanionAppInstalled()) {
            result.error("wearable_not_paired", "No Wear OS companion app installed", null)
            return
        }

        if (!ensureWearableClients()) {
            result.error(wearableUnavailableCode, "Wear OS runtime unavailable", null)
            return
        }

        val localNodeClient = nodeClient
        val localMessageClient = messageClient
        if (localNodeClient == null || localMessageClient == null) {
            result.error(wearableUnavailableCode, "Wear OS runtime unavailable", null)
            return
        }

        val messageData = objectToBytes(call.arguments)
        localNodeClient.connectedNodes.addOnSuccessListener { nodes ->
            nodes.forEach { localMessageClient.sendMessage(it.id, channelName, messageData) }
            result.success(null)
        }.addOnFailureListener { result.error(it.javaClass.simpleName, it.localizedMessage, it) }
    }

    @SuppressLint("VisibleForTests")
    private fun updateApplicationContext(call: MethodCall, result: Result) {
        if (!hasCompanionAppInstalled()) {
            result.error("wearable_not_paired", "No Wear OS companion app installed", null)
            return
        }

        if (!ensureWearableClients()) {
            result.error(wearableUnavailableCode, "Wear OS runtime unavailable", null)
            return
        }

        val client = dataClient
        if (client == null) {
            result.error(wearableUnavailableCode, "Wear OS runtime unavailable", null)
            return
        }

        val eventData = objectToBytes(call.arguments)
        val dataItem = PutDataRequest.create("/$channelName")
        dataItem.data = eventData
        client.putDataItem(dataItem)
            .addOnSuccessListener { result.success(null) }
            .addOnFailureListener { result.error(it.javaClass.simpleName, it.localizedMessage, it) }

    }

    override fun onMessageReceived(message: MessageEvent) {
        try {
            val messageContent = objectFromBytes(message.data)
            channel.invokeMethod("didReceiveMessage", messageContent)
        } catch (_: Exception) {
            Log.w(tag, "Failed to deserialize Wear OS message")
        }
    }

    @SuppressLint("VisibleForTests")
    override fun onDataChanged(dataItems: DataEventBuffer) {
        val currentLocalNodeId = localNodeId ?: return
        dataItems
            .filter {
                it.type == TYPE_CHANGED
                        && it.dataItem.uri.host != currentLocalNodeId
                        && it.dataItem.uri.path == "/$channelName"
            }
            .forEach { item ->
                val eventContent = objectFromBytes(item.dataItem.data!!)
                channel.invokeMethod("didReceiveApplicationContext", eventContent)
            }
    }
}
