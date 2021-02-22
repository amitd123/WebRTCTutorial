package com.adsaff.mywebrtc

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        callToWebRTCMethod()
    }

    private fun hideKeyboardFrom(context: Context, view: View) {
        val imm = context.getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun callToWebRTCMethod() {
        val imageView = findViewById<ImageView>(R.id.connect_button)
        val editText = findViewById<EditText>(R.id.et_room_id)
        imageView.setOnClickListener { view ->
            if (editText.text.toString().isEmpty()) {
                Toast.makeText(applicationContext, R.string.please_enter_a_room_id, Toast.LENGTH_LONG).show()
            } else {
                hideKeyboardFrom(this@MainActivity, view)
                startActivity(Intent(applicationContext, WebRTCActivity::class.java).putExtra("room_id", editText.text.toString()))
            }
        }
    }
}