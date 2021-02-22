package com.adsaff.mywebrtc

import kotlinx.serialization.Serializable
import org.webrtc.IceCandidate

@Serializable
data class candidate(val id : String, val type : String, val candidate: IceCandidate)
