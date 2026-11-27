package com.myra.assistant

import android.app.Application
import com.myra.assistant.data.db.MyraDatabase
import com.myra.assistant.data.preferences.SecureSettingsRepository
import com.myra.assistant.data.repository.MyraRepository
import com.myra.assistant.network.MyraConnectManager
import com.myra.assistant.network.PcCompanionServer
import com.myra.assistant.system.AndroidToolsExecutor
import com.myra.assistant.system.DeviceTelemetryHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class MyraApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var settingsRepository: SecureSettingsRepository
        private set
    lateinit var database: MyraDatabase
        private set
    lateinit var myraRepository: MyraRepository
        private set
    lateinit var telemetryHelper: DeviceTelemetryHelper
        private set
    lateinit var toolsExecutor: AndroidToolsExecutor
        private set
    lateinit var pcCompanionServer: PcCompanionServer
        private set
    lateinit var myraConnectManager: MyraConnectManager
        private set

    private val _incomingCompanionCommands = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 16)
    val incomingCompanionCommands = _incomingCompanionCommands.asSharedFlow()

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SecureSettingsRepository(this)
        database = MyraDatabase.getInstance(this)
        myraRepository = MyraRepository(database)
        telemetryHelper = DeviceTelemetryHelper(this)
        toolsExecutor = AndroidToolsExecutor(this, settingsRepository, myraRepository, telemetryHelper)

        pcCompanionServer = PcCompanionServer(
            onCommandFromPc = { clientName, cmdText ->
                _incomingCompanionCommands.tryEmit("PC:$clientName" to cmdText)
            },
            onClientAuthenticated = { clientName, host ->
                appScope.launch {
                    myraRepository.upsertPairedDevice(
                        com.myra.assistant.data.db.PairedDeviceEntity(
                            deviceId = "pc_${host}_${clientName}",
                            name = clientName,
                            deviceType = "PC_COMPANION",
                            host = host,
                            port = settingsRepository.settings.value.pcCompanionPort,
                            sharedKeyHex = "",
                        ),
                    )
                }
            },
            onStatusLog = { level, msg ->
                appScope.launch { myraRepository.log(level, "PCCompanion", msg) }
            },
        )

        myraConnectManager = MyraConnectManager(
            context = this,
            repository = myraRepository,
            onRemoteMessageReceived = { fromDevice, text ->
                _incomingCompanionCommands.tryEmit("MYRA:$fromDevice" to text)
            },
        )
    }
}
