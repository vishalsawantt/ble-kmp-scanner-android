package com.example.blekmp

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform