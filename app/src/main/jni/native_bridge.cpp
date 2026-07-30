#include <jni.h>
#include <string>
#include <cstring>
#include <cstdio>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/socket.h>
#include <netdb.h>
#include <arpa/inet.h>
#include <dlfcn.h>
#include <dirent.h>
#include <fstream>
#include <sstream>
#include <android/log.h>

#define LOG_TAG "MedGPTSec"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ============================================================
//  SECTION 1: ANTI-ROOT DETECTION
// ============================================================

jboolean checkRootDetected() {
    // 1. Check for well-known root binary paths
    const char* su_paths[] = {
        "/sbin/su",
        "/system/bin/su",
        "/system/xbin/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/system/sd/xbin/su",
        "/system/bin/failsafe/su",
        "/data/local/su",
        "/su/bin/su",
        "/magisk/.magisk",
        "/data/adb/magisk.db",
        "/data/adb/magisk.img"
    };

    for (const char* path : su_paths) {
        struct stat st;
        if (stat(path, &st) == 0) {
            LOGW("Tamper: root indicator found at %s", path);
            return JNI_TRUE;
        }
    }

    // 2. Check for Magisk + Superuser packages via /data/data
    const char* root_pkg_dirs[] = {
        "/data/data/com.topjohnwu.magisk",
        "/data/data/com.kingroot.kinguser",
        "/data/data/com.kingo.root",
        "/data/data/com.noshufou.android.su",
        "/data/data/eu.chainfire.supersu",
        "/data/data/com.koushikdutta.superuser",
        "/data/data/com.dimonvideon.rootvalidator"
    };

    for (const char* dir : root_pkg_dirs) {
        struct stat st;
        if (stat(dir, &st) == 0) {
            LOGW("Tamper: root app installed at %s", dir);
            return JNI_TRUE;
        }
    }

    // 3. Check for test-keys build
    FILE* fp = popen("getprop ro.build.tags 2>/dev/null", "r");
    if (fp) {
        char tags[64] = {0};
        if (fgets(tags, sizeof(tags), fp)) {
            if (strstr(tags, "test-keys")) {
                LOGW("Tamper: test-keys build detected");
                pclose(fp);
                return JNI_TRUE;
            }
        }
        pclose(fp);
    }

    // 4. Check if we can access /data (root-only access)
    FILE* data_check = fopen("/data/.medgpt_secure_check", "r");
    if (data_check != nullptr) {
        fclose(data_check);
        // We could write to /data, which suggests root
        LOGW("Tamper: /data appears writable (possible root)");
        return JNI_TRUE;
    }

    return JNI_FALSE;
}

// ============================================================
//  SECTION 2: ANTI-FRIDA DETECTION
// ============================================================

jboolean checkFridaDetected() {
    // 1. Check for Frida default port (27042)
    int sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock >= 0) {
        struct sockaddr_in addr;
        memset(&addr, 0, sizeof(addr));
        addr.sin_family = AF_INET;
        addr.sin_port = htons(27042);
        addr.sin_addr.s_addr = inet_addr("127.0.0.1");

        if (connect(sock, (struct sockaddr*)&addr, sizeof(addr)) == 0) {
            LOGW("Tamper: Frida port 27042 detected");
            close(sock);
            return JNI_TRUE;
        }
        close(sock);
    }

    // 2. Check for Frida binaries
    const char* frida_paths[] = {
        "/data/local/tmp/frida-server",
        "/data/local/tmp/re.frida.server",
        "/data/local/tmp/frida",
        "/data/local/tmp/frida-agent-32.so",
        "/data/local/tmp/frida-agent-64.so"
    };

    for (const char* path : frida_paths) {
        struct stat st;
        if (stat(path, &st) == 0) {
            LOGW("Tamper: Frida binary at %s", path);
            return JNI_TRUE;
        }
    }

    // 3. Scan /proc/self/maps for Frida/gum libraries
    FILE* maps = fopen("/proc/self/maps", "r");
    if (maps) {
        char line[512];
        while (fgets(line, sizeof(line), maps)) {
            if (strstr(line, "frida") ||
                strstr(line, "gum-js-loop") ||
                strstr(line, "gdbus") ||
                strstr(line, "libgum") ||
                strstr(line, "frida-agent")) {
                LOGW("Tamper: Frida library in memory: %s", line);
                fclose(maps);
                return JNI_TRUE;
            }
        }
        fclose(maps);
    }

    // 4. Scan /proc/self/task for Frida threads
    DIR* task_dir = opendir("/proc/self/task");
    if (task_dir) {
        struct dirent* entry;
        while ((entry = readdir(task_dir)) != nullptr) {
            if (entry->d_name[0] == '.') continue;
            char comm_path[256];
            snprintf(comm_path, sizeof(comm_path),
                     "/proc/self/task/%s/comm", entry->d_name);
            FILE* comm = fopen(comm_path, "r");
            if (comm) {
                char name[64] = {0};
                if (fgets(name, sizeof(name), comm)) {
                    name[strcspn(name, "\n")] = 0;
                    if (strstr(name, "frida") || strstr(name, "gum")) {
                        LOGW("Tamper: Frida thread: %s", name);
                        fclose(comm);
                        closedir(task_dir);
                        return JNI_TRUE;
                    }
                }
                fclose(comm);
            }
        }
        closedir(task_dir);
    }

    return JNI_FALSE;
}

// ============================================================
//  SECTION 3: ANTI-DEBUG DETECTION
// ============================================================

jboolean checkDebugDetected() {
    // 1. Check TracerPid in /proc/self/status
    FILE* status = fopen("/proc/self/status", "r");
    if (status) {
        char line[256];
        while (fgets(line, sizeof(line), status)) {
            if (strstr(line, "TracerPid:")) {
                int tracer_pid = 0;
                sscanf(line, "TracerPid:\t%d", &tracer_pid);
                if (tracer_pid != 0) {
                    LOGW("Tamper: Debugger detected (TracerPid=%d)", tracer_pid);
                    fclose(status);
                    return JNI_TRUE;
                }
                break;
            }
        }
        fclose(status);
    }

    // 2. Check for JDWP support (debug port)
    char jdwp_path[128];
    snprintf(jdwp_path, sizeof(jdwp_path),
             "/proc/self/fd/%d", getpid());
    struct stat st;
    if (stat(jdwp_path, &st) == 0) {
        // Check if any fd points to JDWP
        DIR* fd_dir = opendir("/proc/self/fd");
        if (fd_dir) {
            struct dirent* entry;
            while ((entry = readdir(fd_dir)) != nullptr) {
                if (entry->d_type == DT_LNK) {
                    char link[256] = {0};
                    char fd_path[128];
                    snprintf(fd_path, sizeof(fd_path),
                             "/proc/self/fd/%s", entry->d_name);
                    readlink(fd_path, link, sizeof(link) - 1);
                    if (strstr(link, "jdwp") || strstr(link, "gdb")) {
                        LOGW("Tamper: Debug socket found: %s", link);
                        closedir(fd_dir);
                        return JNI_TRUE;
                    }
                }
            }
            closedir(fd_dir);
        }
    }

    // 3. Check for debuggerd
    char debuggerd_path[128];
    snprintf(debuggerd_path, sizeof(debuggerd_path),
             "/proc/%d/cmdline", getppid());
    FILE* pp = fopen(debuggerd_path, "r");
    if (pp) {
        char name[64] = {0};
        if (fgets(name, sizeof(name), pp)) {
            if (strstr(name, "debuggerd")) {
                LOGW("Tamper: debuggerd parent detected");
                fclose(pp);
                return JNI_TRUE;
            }
        }
        fclose(pp);
    }

    return JNI_FALSE;
}

// ============================================================
//  SECTION 4: COMBINED CHECK
// ============================================================

jboolean checkAllTamper() {
    if (checkRootDetected()) return JNI_TRUE;
    if (checkFridaDetected()) return JNI_TRUE;
    if (checkDebugDetected()) return JNI_TRUE;
    return JNI_FALSE;
}

// ============================================================
//  SECTION 5: JNI EXPORTS
// ============================================================

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_medgpt_app_SecurityManager_nativeCheckRoot(JNIEnv* env, jobject thiz) {
    return checkRootDetected();
}

JNIEXPORT jboolean JNICALL
Java_com_medgpt_app_SecurityManager_nativeCheckFrida(JNIEnv* env, jobject thiz) {
    return checkFridaDetected();
}

JNIEXPORT jboolean JNICALL
Java_com_medgpt_app_SecurityManager_nativeCheckDebug(JNIEnv* env, jobject thiz) {
    return checkDebugDetected();
}

JNIEXPORT jboolean JNICALL
Java_com_medgpt_app_SecurityManager_nativeCheckAll(JNIEnv* env, jobject thiz) {
    return checkAllTamper();
}

JNIEXPORT jstring JNICALL
Java_com_medgpt_app_SecurityManager_nativeGetStatus(JNIEnv* env, jobject thiz) {
    std::string status;
    status += "root:" + std::to_string(checkRootDetected());
    status += ",frida:" + std::to_string(checkFridaDetected());
    status += ",debug:" + std::to_string(checkDebugDetected());
    return env->NewStringUTF(status.c_str());
}

} // extern "C"
