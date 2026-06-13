package com.kunk.singbox.service

import io.nekohasekai.libbox.*
import kotlinx.coroutines.*
class SingBoxService : SingBoxServicePart4() {

    companion object : SingBoxServiceCompanionPart1() {
    }
}

enum class ServiceState {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING
}
