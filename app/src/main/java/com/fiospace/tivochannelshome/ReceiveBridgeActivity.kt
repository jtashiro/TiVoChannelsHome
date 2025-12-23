package com.fiospace.tivochannelshome

import android.app.Activity
import android.os.Bundle
import android.util.Log

class ReceiveBridgeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("ReceiveBridgeActivity", "Invocation ignored: reorder feature removed; activity present only for compatibility")
        finish()
    }
}
