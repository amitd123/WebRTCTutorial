package com.adsaff.mywebrtc

import android.Manifest
import android.app.ProgressDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import com.adsaff.mywebrtc.AppRTCAudioManager.AudioManagerEvents
import com.adsaff.mywebrtc.SslUtils.getSslContextForCertificateFile
import com.adsaff.mywebrtc.databinding.ActivityWebSocketBinding
import io.socket.client.Socket
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.OkHttpClient
import org.json.JSONException
import org.json.JSONObject
import org.webrtc.*
import org.webrtc.PeerConnection.*
import pub.devrel.easypermissions.AfterPermissionGranted
import pub.devrel.easypermissions.EasyPermissions
import java.net.URISyntaxException
import java.security.KeyManagementException
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.*
import javax.net.ssl.*


class WebRTCActivity : AppCompatActivity() {
    private var socket: Socket? = null
    private var binding: ActivityWebSocketBinding? = null
    private var peerConnection: PeerConnection? = null
    private var rootEglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var videoTrackFromCamera: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var audioSource: AudioSource? = null
    private var audioConstraints: MediaConstraints? = null
    private val enableAudio = false
    private var audioManager: AppRTCAudioManager? = null
    private var onMicEnabled = true
    private var roomId: String? = null
    private var progressDialog: ProgressDialog? = null
    private var videoCapturer: VideoCapturer? = null
    private var localDataChannel: DataChannel? = null
    private var mWebSocket: WebSocket? = null
    lateinit var mHandler: Handler
    lateinit var mainHandler: Handler
//    lateinit var BtnSendOffer :Button
//    lateinit var BtnSendAnswer :Button
    var peerId = ""
    var availablePeerId = ""
    var stunServer = "stun:stun.l.google.com:19302"
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_web_socket)
        setSupportActionBar(binding!!.toolbar)
        progressDialog = ProgressDialog(this@WebRTCActivity)
        progressDialog!!.setMessage("Calling.... \nPlease wait. another person will connect soon.")
        progressDialog!!.setCancelable(true)
        roomId = intent.getStringExtra("room_id")
        start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this)
    }

    @AfterPermissionGranted(RC_CALL)
    fun start() {
        val perms = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (EasyPermissions.hasPermissions(this, *perms)) {
            startAudioStreaming()
            initializeSurfaceViews()
            initializePeerConnectionFactory()
            createVideoTrackFromCameraAndShowIt()
            initializePeerConnections()
            connectToSignallingServer()
            startStreamingVideo()
            enableMic()
            binding!!.BtnSendOffer.setOnClickListener{
                if (availablePeerId != "") {
                    offer(availablePeerId)
                }
            }
            binding!!.BtnSendAnswer.setOnClickListener{
                doAnswer("answer", availablePeerId)
            }
            binding!!.buttonCallDisconnect.setOnClickListener { finishVideoCall() }
            binding!!.buttonCallToggleMic.setOnClickListener {
                localAudioTrack!!.setEnabled(true)
                binding!!.buttonCallToggleMic.alpha = if (onToggleMic()) 1.0f else 0.3f
            }
            binding!!.buttonCallSwitchCamera.setOnClickListener { switchCamera() }
        } else {
            EasyPermissions.requestPermissions(this, "Need some permissions", RC_CALL, *perms)
        }
    }

    private fun captureToTexture(): Boolean {
        return true
    }

    fun enableMic() {
        binding!!.buttonCallToggleMic.alpha = if (true) 1.0f else 0.3f
        localAudioTrack!!.setEnabled(true)
    }

    fun disableVideo() {
        videoTrackFromCamera!!.setEnabled(false)
    }

    fun onToggleMic(): Boolean {
        onMicEnabled = if (onMicEnabled) {
            false
        } else {
            true
        }
        return onMicEnabled
    }

    fun finishVideoCall() {
        if (peerConnection != null) {
            peerConnection!!.close()
        }
        if (socket != null) {
            socket!!.disconnect()
        }
    }

    fun startAudioStreaming() {
        audioConstraints = MediaConstraints()
        audioConstraints!!.mandatory.add(
                MediaConstraints.KeyValuePair(AUDIO_LEVEL_CONTROL_CONSTRAINT, "true"))
        audioManager = AppRTCAudioManager.create(this)
        audioManager!!.start(AudioManagerEvents { device, availableDevices -> onAudioManagerDevicesChanged(device, availableDevices) })
    }

    private fun onAudioManagerDevicesChanged(
            device: AppRTCAudioManager.AudioDevice, availableDevices: Set<AppRTCAudioManager.AudioDevice>) {
    }

    private fun createAudioTrack(): AudioTrack? {
        audioSource = factory!!.createAudioSource(audioConstraints)
        localAudioTrack = factory!!.createAudioTrack(AUDIO_TRACK_ID, audioSource)
        localAudioTrack!!.setEnabled(enableAudio)
        return localAudioTrack
    }

    private fun connectToSignallingServer() {
        progressDialog!!.show()
        try {

            val sslUtils = getSslContextForCertificateFile(this, "certificate.pem")
            var tlsSocketFactory: TLSSocketFactory? = null
            try {
                tlsSocketFactory = TLSSocketFactory()
            } catch (e: KeyManagementException) {
                e.printStackTrace()
            } catch (e: NoSuchAlgorithmException) {
                e.printStackTrace()
            }

            /*val httpClient: OkHttpClient = OkHttpClient.Builder()
                    .sslSocketFactory(sslUtils.getSocketFactory())
                    //sslSocketFactory(sslUtils.getSocketFactory())
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()*/
            val request = Request.Builder()
                    .url("wss://mediaserver.cachy.com/p2p/")
                    .build()
            mWebSocket = getUnsafeOkHttpClient()?.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.e(TAG, " WebSocket onOpen")
                    mainHandler = Handler(Looper.getMainLooper())
                    mainHandler.post(fireKeepAlive)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.e(TAG, "onMessage text $text")
                    onMessage(text)
                }


                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.e(TAG, " WebSocket onClosing")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d("janus", "onClosed $reason")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, " WebSocket onFailure$t" + " response $response ")
                }
            })

            //  socket = IO.socket("https://videocall-webrtc-android.herokuapp.com/")
            /*socket = IO.socket("wss://mediaserver.cachy.com/p2p/")
            socket!!.on(Socket.EVENT_CONNECT, Emitter.Listener { args: Array<Any?>? ->
                Log.w(TAG, "connectToSignallingServer: Socket Server Connected. ")
                socket!!.emit("create or join", roomId)
            }).on("new peer") { args: Array<Any> ->
                val peerId = args[0] as String
                Log.w(TAG, "connectToSignallingServer: New peer added with peer id - $peerId")
                offer(peerId)
            }.on("signal") { args: Array<Any> ->
                try {
                    val signalObject = args[0] as JSONObject
                    val type = signalObject.getString("type")
                    val peerId = signalObject.getString("peerId")
                    Log.w(TAG, "connectToSignallingServer: $type  received with peer id -  $peerId")
                    if (type.equals("OFFER", ignoreCase = true)) {
                        peerConnection!!.setRemoteDescription(SimpleSdpObserver(), SessionDescription(SessionDescription.Type.OFFER, signalObject.getString("sdp")))
                        doAnswer(type, peerId)
                    } else if (type.equals("ANSWER", ignoreCase = true)) {
                        peerConnection!!.setRemoteDescription(SimpleSdpObserver(), SessionDescription(SessionDescription.Type.ANSWER, signalObject.getString("sdp")))
                    }
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
            }.on("signal candidate") { args: Array<Any> ->
                Log.w(TAG, "connectToSignallingServer: receiving candidates$args")
                progressDialog!!.dismiss()
                try {
                    val signalObject = args[0] as JSONObject
                    val type = signalObject.getString("type")
                    if (type == "candidate") {
                        Log.w(TAG, "connectToSignallingServer: receiving candidates")
                        val candidate = IceCandidate(signalObject.getString("id"), signalObject.getInt("label"), signalObject.getString("candidate"))
                        peerConnection!!.addIceCandidate(candidate)
                    }
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
            }.on(Socket.EVENT_DISCONNECT) { args: Array<Any?>? ->
                Log.w(TAG, "connectToSignallingServer: disconnect")
                finish()
            }
            socket!!.connect()*/
        } catch (e: URISyntaxException) {
            e.printStackTrace()
        }
    }

    private fun onMessage(message: String) {
        try {
            Log.d("onMessage", message)
        //    availablePeerId = ""
            val jo = JSONObject(message)
            val type = jo.getString("type")
            /* switch (type){
                "":
            }*/
            if (type == "hello") {
                availablePeerId = jo.getString("available_nodes")
                peerId = jo.getString("id")

            } else if (type == "candidate") {
                val jo2 = JSONObject("candidate")
                val candidate = IceCandidate(jo2.getString("sdpMid"), jo2.getInt("sdpMLineIndex"), jo2.getString("candidate"))
                peerConnection!!.addIceCandidate(candidate)
            } else if (type == "iceServers") {
                //TODO
            } else if (type == "bye") {
            } else if (type == "offer") {
                availablePeerId = jo.getString("id")
                val sdp = jo.getString("sdp")
              //  peerId = jo.getString("id")
                Log.w(TAG, "connectToSignallingServer: $type  received with peer id -  $peerId")
                peerConnection!!.setRemoteDescription(SimpleSdpObserver(), SessionDescription(SessionDescription.Type.OFFER, sdp))
                doAnswer("answer", availablePeerId)
            } else if (type == "answer") {
                availablePeerId = jo.getString("id")
                val sdp = jo.getString("sdp")
                peerConnection!!.setRemoteDescription(SimpleSdpObserver(), SessionDescription(SessionDescription.Type.ANSWER, sdp))
            } else if (type == "candidate") {
            }
        } catch (e: Exception) {
        }
        //
    }

    private fun keepAlive() {
        // val transaction = randomString(12)
        val msg = JSONObject()
        try {
            msg.putOpt("time", "timer")
        } catch (e: JSONException) {
            e.printStackTrace()
        }
        mWebSocket!!.send(msg.toString())
        mainHandler.postDelayed(fireKeepAlive, 5000)
    }

    private val fireKeepAlive = object : Runnable {
        override fun run() {
            keepAlive()
        }
    }

    private fun offer(peerId: String) {
        val sdpMediaConstraints = MediaConstraints()
        sdpMediaConstraints.mandatory.add(
                MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        peerConnection!!.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sessionDescription: SessionDescription) {
                peerConnection!!.setLocalDescription(SimpleSdpObserver(), sessionDescription)
                val message = JSONObject()
                try {
                    message.put("type", "offer")
                    message.put("id", peerId)
                    message.put("sdp", sessionDescription.description)
                    mWebSocket!!.send(message.toString())
                    // socket!!.emit("signal", peerId, message)
                    Log.w(TAG, "connectToSignallingServer: Offer Emitted !!")
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
            }
        }, sdpMediaConstraints)
    }

    private fun doAnswer(type: String, peerId: String) {
        peerConnection!!.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sessionDescription: SessionDescription) {
                peerConnection!!.setLocalDescription(SimpleSdpObserver(), sessionDescription)
                val message = JSONObject()
                try {
                    message.put("type", type)
                    message.put("id", peerId)
                    message.put("sdp", sessionDescription.description)
                    //  socket!!.emit("signal", peerId, message)
                    mWebSocket?.send(message.toString())
                    Log.w(TAG, "connectToSignallingServer: Answer Emitted !!")
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
            }
        }, MediaConstraints())
    }

    private fun initializeSurfaceViews() {
        rootEglBase = EglBase.create()
        binding!!.surfaceView.init(rootEglBase!!.getEglBaseContext(), null)
        binding!!.surfaceView.setEnableHardwareScaler(true)
        binding!!.surfaceView.setMirror(true)
        binding!!.surfaceView2.init(rootEglBase!!.getEglBaseContext(), null)
        binding!!.surfaceView2.setEnableHardwareScaler(true)
        binding!!.surfaceView2.setMirror(true)
    }

    private fun initializePeerConnectionFactory() {
        PeerConnectionFactory.initializeAndroidGlobals(this, true, true, true)
        factory = PeerConnectionFactory(null)
        factory!!.setVideoHwAccelerationOptions(rootEglBase!!.eglBaseContext, rootEglBase!!.eglBaseContext)
    }

    private fun createVideoTrackFromCameraAndShowIt() {
        videoCapturer = createVideoCapturer()
        val videoSource = factory!!.createVideoSource(videoCapturer)
        videoCapturer!!.startCapture(VIDEO_RESOLUTION_WIDTH, VIDEO_RESOLUTION_HEIGHT, FPS)
        videoTrackFromCamera = factory!!.createVideoTrack(VIDEO_TRACK_ID, videoSource)
        videoTrackFromCamera!!.setEnabled(true)
        videoTrackFromCamera!!.addRenderer(VideoRenderer(binding!!.surfaceView))
    }

    private fun createVideoCapturer(): VideoCapturer? {
        val videoCapturer: VideoCapturer?
        videoCapturer = if (useCamera2()) {
            createCameraCapturer(Camera2Enumerator(this))
        } else {
            createCameraCapturer(Camera1Enumerator(true))
        }
        return videoCapturer
    }

    private fun createCameraCapturer(enumerator: CameraEnumerator): VideoCapturer? {
        val deviceNames = enumerator.deviceNames
        for (deviceName in deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                val videoCapturer: VideoCapturer? = enumerator.createCapturer(deviceName, null)
                if (videoCapturer != null) {
                    return videoCapturer
                }
            }
        }
        for (deviceName in deviceNames) {
            if (!enumerator.isFrontFacing(deviceName)) {
                val videoCapturer: VideoCapturer? = enumerator.createCapturer(deviceName, null)
                if (videoCapturer != null) {
                    return videoCapturer
                }
            }
        }
        return null
    }

    /*
     * Read more about Camera2 here
     * https://developer.android.com/reference/android/hardware/camera2/package-summary.html
     * */
    private fun useCamera2(): Boolean {
        return Camera2Enumerator.isSupported(this)
    }

    private fun initializePeerConnections() {
        peerConnection = createPeerConnection(factory)

    }

    fun switchCamera() {
        val cameraVideoCapturer = videoCapturer as CameraVideoCapturer
        cameraVideoCapturer.switchCamera(null)
    }

    private fun createPeerConnection(factory: PeerConnectionFactory?): PeerConnection {
        val iceServers = ArrayList<IceServer>()
        iceServers.add(IceServer("turn:192.158.29.39:3478?transport=udp","7Jp1GVDegbMGTilyj9OmmluHSifwA_KIA43fSIL4STGOio9wE3_rkFgLfhGQ38p6AAAAAGAvRUxBYmhpc2hlaw==","031c78f8-726f-11eb-a92b-0242ac140004"))
      //  iceServers.add(IceServer("turn:bn-turn1.xirsys.com:3478?transport=tcp","7Jp1GVDegbMGTilyj9OmmluHSifwA_KIA43fSIL4STGOio9wE3_rkFgLfhGQ38p6AAAAAGAvRUxBYmhpc2hlaw==","031c78f8-726f-11eb-a92b-0242ac140004"))
     //   iceServers.add(IceServer("stun:bn-turn1.xirsys.com"))
        iceServers.add(IceServer("stun:bn-turn1.xirsys.com"))
        //iceServers.add(IceServer(stunServer))
        val rtcConfig = RTCConfiguration(iceServers)
        val pcConstraints = MediaConstraints()

        val pcObserver: PeerConnection.Observer = object : PeerConnection.Observer {
            override fun onSignalingChange(signalingState: SignalingState) {
                Log.w(TAG, "onSignalingChange: $signalingState")
            }

            override fun onIceConnectionChange(iceConnectionState: IceConnectionState) {
                Log.w(TAG, "onIceConnectionChange: $iceConnectionState")

            }

            override fun onIceConnectionReceivingChange(b: Boolean) {
                Log.w(TAG, "onIceConnectionReceivingChange: $b")
            }

            override fun onIceGatheringChange(iceGatheringState: IceGatheringState) {
                Log.w(TAG, "onIceGatheringChange: $iceGatheringState")
            }

            override fun onIceCandidate(iceCandidate: IceCandidate) {
                Log.w(TAG, "onIceCandidate: ")
                val message = JSONObject()
                val message1 = JSONObject()
                val cand : candidate = candidate(availablePeerId,"candidate",iceCandidate)
                try {
                    message.put("candidate",iceCandidate.sdp)
                    message.put("sdpMLineIndex",iceCandidate.sdpMLineIndex)
                    message.put("sdpMid",iceCandidate.sdpMid)
                  //  message.put("candidate",iceCandidate)
                    message1.put("id",availablePeerId)
                    message1.put("type","candidate")
                    message1.put("candidate",message)
                    Log.w(TAG, "onIceCandidate: sending candidate $message1")
                    //socket!!.emit("candidate", message)
                    //val a : SerializationStrategy<candidate>
                    //val jsonData = Json.encodeToString(a , cand)

                    mWebSocket!!.send(message1.toString())
                    Log.d("****ICE CANDIDATE SENDER******", message1.toString())
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
            }

            override fun onIceCandidatesRemoved(iceCandidates: Array<IceCandidate>) {
                Log.w(TAG, "onIceCandidatesRemoved: $iceCandidates")
            }

            override fun onAddStream(mediaStream: MediaStream) {
                Log.w(TAG, "connectToSignallingServer:  media Stream Received :  " + mediaStream.videoTracks.size)
                val remoteVideoTrack = mediaStream.videoTracks[0]
                val audioTrack = mediaStream.audioTracks[0]
                Log.w(TAG, "connectToSignallingServer:  Audio Stream Received :  " + mediaStream.audioTracks[0])
                audioTrack.setEnabled(true)
                remoteVideoTrack.setEnabled(true)
                remoteVideoTrack.addRenderer(VideoRenderer(binding!!.surfaceView2))
            }

            override fun onRemoveStream(mediaStream: MediaStream) {
                Log.w(TAG, "onRemoveStream: ")
            }

            override fun onDataChannel(dataChannel: DataChannel) {
                Log.w(TAG, "onDataChannel: $dataChannel")
            }

            override fun onRenegotiationNeeded() {
                Log.w(TAG, "onRenegotiationNeeded: ")
            }
        }
        return factory!!.createPeerConnection(rtcConfig, pcConstraints, pcObserver)
    }

    private fun startStreamingVideo() {
        val mediaStream = factory!!.createLocalMediaStream("ARDAMS")
        mediaStream.addTrack(videoTrackFromCamera)
        mediaStream.addTrack(createAudioTrack())
        Log.w(TAG, "connectToSignallingServer:   " + "Adding local media Stream")
        peerConnection!!.addStream(mediaStream)
        localDataChannel = peerConnection!!.createDataChannel("sendDataChannel", DataChannel.Init())
        localDataChannel!!.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(l: Long) {}
            override fun onStateChange() {
                Log.d(TAG, "onStateChange: " + localDataChannel!!.state().toString())
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary) {
                    Log.d(TAG, "Received_binary msg over $localDataChannel")
                    return
                }
                val data = buffer.data
                val bytes = ByteArray(data.capacity())
                data[bytes]
                val strData = String(bytes)
                Log.d(TAG, "Got_msg: $strData over $localDataChannel")
            }
        })
    }

    override fun onDestroy() {
        if (socket != null) {
            if (peerConnection != null) {
                peerConnection!!.close()
            }
            if (socket != null) {
                socket!!.disconnect()
            }
            //   socket!!.emit("disconnect")
        }
        super.onDestroy()
    }

    companion object {
        private val TAG = WebRTCActivity::class.java.simpleName
        private const val RC_CALL = 111
        const val VIDEO_TRACK_ID = "ARDAMSv0"
        const val AUDIO_TRACK_ID = "ARDAMSa0"
        private const val AUDIO_LEVEL_CONTROL_CONSTRAINT = "levelControl"
        const val VIDEO_RESOLUTION_WIDTH = 1280
        const val VIDEO_RESOLUTION_HEIGHT = 720
        const val FPS = 30
    }

    private fun createSocketFactory(protocols: List<String>) =
            SSLContext.getInstance(protocols[0]).apply {
                val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                    override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) = Unit
                    override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) = Unit
                })
                init(null, trustAllCerts, SecureRandom())
            }.socketFactory


    private fun getUnsafeOkHttpClient(): OkHttpClient? {
        return try {
            // Create a trust manager that does not validate certificate chains
            val trustAllCerts = arrayOf<TrustManager>(
                    object : X509TrustManager {
                        @Throws(CertificateException::class)
                        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                        }

                        @Throws(CertificateException::class)
                        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                        }

                        override fun getAcceptedIssuers(): Array<X509Certificate> {
                            return arrayOf()
                        }
                    }
            )

            // Install the all-trusting trust manager
            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, SecureRandom())
            // Create an ssl socket factory with our all-trusting manager
            val sslSocketFactory: SSLSocketFactory = sslContext.socketFactory
            val builder = OkHttpClient.Builder()
            builder.sslSocketFactory(sslSocketFactory, trustAllCerts[0] as X509TrustManager)
            builder.hostnameVerifier(object : HostnameVerifier {
                override fun verify(hostname: String?, session: SSLSession?): Boolean {
                    return true
                }
            })
            builder.build()
        } catch (e: java.lang.Exception) {
            throw RuntimeException(e)
        }
    }
}