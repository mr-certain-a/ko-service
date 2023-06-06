import kotlinx.cinterop.invoke
import kotlinx.cinterop.*
import me.archinamon.fileio.*
import platform.windows.*

const val SERVICE_NAME = "ko-service"

var running = true
var active = true
var fin = false

var hServiceStatus: SERVICE_STATUS_HANDLE? = null

@ExperimentalUnsignedTypes
fun serviceControlStop(ss: SERVICE_STATUS) {
    log("stop")

    ss.dwCurrentState = SERVICE_STOP_PENDING.toUInt()
    SetServiceStatus(hServiceStatus, ss.ptr)

    running = false

    while(!fin) {
        Sleep(1000)
    }

    ss.dwCurrentState = SERVICE_STOPPED.toUInt()
    SetServiceStatus(hServiceStatus, ss.ptr)
}

@ExperimentalUnsignedTypes
fun serviceControlPause(ss: SERVICE_STATUS) {
    log("pause")

    ss.dwCurrentState = SERVICE_PAUSE_PENDING.toUInt()
    SetServiceStatus(hServiceStatus, ss.ptr)

    active = false

    ss.dwCurrentState = SERVICE_PAUSED.toUInt()
    ss.dwControlsAccepted = SERVICE_STOPPED.or(SERVICE_ACCEPT_PAUSE_CONTINUE).toUInt()

    SetServiceStatus(hServiceStatus, ss.ptr)
}

@ExperimentalUnsignedTypes
fun serviceControlContinue(ss: SERVICE_STATUS) {
    log("continue")

    ss.dwCurrentState = SERVICE_START_PENDING.toUInt()

    SetServiceStatus(hServiceStatus, ss.ptr)

    active = true

    ss.dwCurrentState = SERVICE_RUNNING.toUInt()
    ss.dwControlsAccepted = SERVICE_STOPPED.or(SERVICE_ACCEPT_PAUSE_CONTINUE).toUInt()

    SetServiceStatus(hServiceStatus, ss.ptr)
}

@ExperimentalUnsignedTypes
fun handlerEx(
    dwControl: DWORD,
    @Suppress("UNUSED_PARAMETER") dwEventType: DWORD,
    @Suppress("UNUSED_PARAMETER") lpEventData: LPVOID?,
    @Suppress("UNUSED_PARAMETER") lpContext: LPVOID? ): DWORD {

    memScoped {
        val ss = alloc<SERVICE_STATUS>().apply {
            dwServiceType = SERVICE_WIN32_OWN_PROCESS.toUInt()
            dwWin32ExitCode = NO_ERROR.toUInt()
            dwServiceSpecificExitCode = 0u
            dwCheckPoint = 0u
            dwWaitHint = 0u
            dwControlsAccepted = SERVICE_ACCEPT_STOP.toUInt()
        }

        when (dwControl.toInt()) {
            SERVICE_CONTROL_STOP -> serviceControlStop(ss)
            SERVICE_CONTROL_PAUSE -> serviceControlPause(ss)
            SERVICE_CONTROL_CONTINUE -> serviceControlContinue(ss)
            else -> return ERROR_CALL_NOT_IMPLEMENTED.toUInt()
        }
    }
    return NO_ERROR.toUInt()
}

@ExperimentalUnsignedTypes
fun serviceMain(@Suppress("UNUSED_PARAMETER") dwArgc: DWORD,
                @Suppress("UNUSED_PARAMETER") pszArgv: CPointer<LPWSTRVar>?) {
    memScoped {
        RegisterServiceCtrlHandlerEx!!(
            SERVICE_NAME.wcstr.ptr, staticCFunction(::handlerEx), NULL)?.let {
            hServiceStatus = it

            log("pending...")
            val ss = alloc<SERVICE_STATUS>().apply {
                dwServiceType = SERVICE_WIN32_OWN_PROCESS.toUInt()
                dwWin32ExitCode = NO_ERROR.toUInt()

                dwCurrentState = SERVICE_START_PENDING.toUInt()
                dwCheckPoint = 1u
                dwWaitHint = 3000u
                dwControlsAccepted = SERVICE_ACCEPT_STOP.toUInt()
            }
            SetServiceStatus(hServiceStatus, ss.ptr)

            log("running")
            ss.apply {
                dwCurrentState  = SERVICE_RUNNING.toUInt()
                dwCheckPoint = 0u
                dwWaitHint = 0u
                dwControlsAccepted = SERVICE_ACCEPT_PAUSE_CONTINUE.or(SERVICE_ACCEPT_STOP).toUInt()
            }
            SetServiceStatus(hServiceStatus, ss.ptr)

            while (running) {
                if(active)
                    log("$SERVICE_NAME is running.")

                Sleep(1000)
            }

            log("End of Kotlin Service.")
            fin = true

        } ?: log("RegisterServiceCtrlHandler failed. ${GetLastError()}")
    }
}

fun modulePath() =
    memScoped {
        allocArray<WCHARVar>(256).apply {
            GetModuleFileName!!(null, this, 256u)
            PathRemoveFileSpec!!(this)
        }.toKString()
    }

const val CRLF = "\r\n"
fun log(str: String) {
    println(str)
    File("${modulePath()}/ko-service.log").apply {
        if(!exists())
            createNewFile()
        appendText("$str$CRLF")
    }
}

@ExperimentalUnsignedTypes
fun main() {
    memScoped {
        StartServiceCtrlDispatcher!!(alloc<SERVICE_TABLE_ENTRYW>().apply {
            lpServiceName = SERVICE_NAME.wcstr.ptr
            lpServiceProc = staticCFunction(::serviceMain)
        }.ptr)
    }
}