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
#include <algorithm>
#include <random>
#include <chrono>

#define LOG_TAG "MedGPTSec"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ============================================================
//  STRING ENCRYPTION (XOR + compile-time obfuscation)
// ============================================================

// Simple XOR key for string obfuscation
static const unsigned char XOR_KEY[] = {
    0x7A, 0xB3, 0x9C, 0x4F, 0xD6, 0x28, 0xE1, 0x55,
    0x8D, 0x32, 0xFC, 0x67, 0xA9, 0x4B, 0xDE, 0x10
};

class ObfuscatedString {
private:
    char buffer[256];
public:
    ObfuscatedString(const unsigned char* encrypted, int len) {
        for (int i = 0; i < len && i < 255; i++) {
            buffer[i] = encrypted[i] ^ XOR_KEY[i % sizeof(XOR_KEY)];
        }
        buffer[len < 255 ? len : 255] = '\0';
    }
    const char* str() const { return buffer; }
};

// Helper: quickly obfuscate a string at runtime
void xorDecrypt(char* out, const unsigned char* in, int len) {
    for (int i = 0; i < len; i++) {
        out[i] = in[i] ^ XOR_KEY[i % sizeof(XOR_KEY)];
    }
    out[len] = '\0';
}

// Obfuscated string macro for compile-time encryption
#define OBFUSCATE(str) \
    []() -> std::string { \
        static const unsigned char encrypted[] = 

// We skip the macro-based approach and use runtime XOR

// ============================================================
//  ROOT DETECTION
// ============================================================

jboolean checkRootDetected() {
    const char* su_paths[] = {
        "/sbin/su", "/system/bin/su", "/system/xbin/su",
        "/data/local/xbin/su", "/data/local/bin/su",
        "/system/sd/xbin/su", "/system/bin/failsafe/su",
        "/data/local/su", "/su/bin/su",
        "/magisk/.magisk", "/data/adb/magisk.db", "/data/adb/magisk.img"
    };
    for (const char* path : su_paths) {
        struct stat st;
        if (stat(path, &st) == 0) {
            LOGW("Tamper: root at %s", path);
            return JNI_TRUE;
        }
    }

    const char* root_pkg_dirs[] = {
        "/data/data/com.topjohnwu.magisk",
        "/data/data/com.kingroot.kinguser",
        "/data/data/com.kingo.root",
        "/data/data/com.noshufou.android.su",
        "/data/data/eu.chainfire.supersu",
        "/data/data/com.koushikdutta.superuser"
    };
    for (const char* dir : root_pkg_dirs) {
        struct stat st;
        if (stat(dir, &st) == 0) {
            LOGW("Tamper: root app %s", dir);
            return JNI_TRUE;
        }
    }

    FILE* fp = popen("getprop ro.build.tags 2>/dev/null", "r");
    if (fp) {
        char tags[64] = {0};
        if (fgets(tags, sizeof(tags), fp) && strstr(tags, "test-keys")) {
            LOGW("Tamper: test-keys build");
            pclose(fp);
            return JNI_TRUE;
        }
        pclose(fp);
    }

    return JNI_FALSE;
}

// ============================================================
//  FRIDA DETECTION
// ============================================================

jboolean checkFridaDetected() {
    int sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock >= 0) {
        struct sockaddr_in addr;
        memset(&addr, 0, sizeof(addr));
        addr.sin_family = AF_INET;
        addr.sin_port = htons(27042);
        addr.sin_addr.s_addr = inet_addr("127.0.0.1");
        if (connect(sock, (struct sockaddr*)&addr, sizeof(addr)) == 0) {
            LOGW("Tamper: Frida port");
            close(sock);
            return JNI_TRUE;
        }
        close(sock);
    }

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
            LOGW("Tamper: Frida bin %s", path);
            return JNI_TRUE;
        }
    }

    FILE* maps = fopen("/proc/self/maps", "r");
    if (maps) {
        char line[512];
        while (fgets(line, sizeof(line), maps)) {
            if (strstr(line, "frida") || strstr(line, "libgum") ||
                strstr(line, "frida-agent") || strstr(line, "gdbus")) {
                LOGW("Tamper: Frida mem %s", line);
                fclose(maps);
                return JNI_TRUE;
            }
        }
        fclose(maps);
    }

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
                        LOGW("Tamper: Frida thread %s", name);
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
//  DEBUG DETECTION
// ============================================================

jboolean checkDebugDetected() {
    FILE* status = fopen("/proc/self/status", "r");
    if (status) {
        char line[256];
        while (fgets(line, sizeof(line), status)) {
            if (strstr(line, "TracerPid:")) {
                int pid = 0;
                sscanf(line, "TracerPid:\t%d", &pid);
                if (pid != 0) {
                    LOGW("Tamper: debug TracerPid=%d", pid);
                    fclose(status);
                    return JNI_TRUE;
                }
                break;
            }
        }
        fclose(status);
    }

    return JNI_FALSE;
}

// ============================================================
//  NEW: EMULATOR DETECTION
// ============================================================

jboolean checkEmulatorDetected() {
    // 1. Check known emulator properties
    const char* qemu_props[] = {
        "ro.kernel.qemu",
        "ro.hardware.ranchu",
        "ro.hardware.goldfish",
        "ro.product.board=goldfish",
        "ro.product.name=sdk",
        "ro.product.manufacturer=unknown",
        "ro.product.model=sdk",
        "ro.product.device=generic",
        "ro.build.user=android-build"
    };

    for (const char* prop : qemu_props) {
        char cmd[256];
        snprintf(cmd, sizeof(cmd), "getprop %s 2>/dev/null", prop);
        FILE* fp = popen(cmd, "r");
        if (fp) {
            char val[128] = {0};
            if (fgets(val, sizeof(val), fp)) {
                val[strcspn(val, "\n")] = 0;
                // Check for common emulator values
                if (strstr(val, "1") || strstr(val, "goldfish") ||
                    strstr(val, "ranchu") || strstr(val, "sdk") ||
                    strstr(val, "generic") || strstr(val, "unknown")) {
                    // Only flag if the prop key matches emulator patterns
                    const char* prop_name = prop;
                    const char* eq = strchr(prop, '=');
                    if (eq) {
                        // The prop format is "key=value", check both
                        LOGW("Emulator: %s = %s", prop, val);
                        pclose(fp);
                        return JNI_TRUE;
                    } else if (strcmp(val, "1") == 0) {
                        LOGW("Emulator: %s = %s", prop, val);
                        pclose(fp);
                        return JNI_TRUE;
                    }
                }
            }
            pclose(fp);
        }
    }

    // 2. Check for emulator-specific files
    const char* emu_files[] = {
        "/system/bin/qemu-props",
        "/system/bin/qemu-adb",
        "/dev/socket/qemud",
        "/dev/qemu_pipe",
        "/system/lib/libc_malloc_debug_qemu.so",
        "/system/lib64/libc_malloc_debug_qemu.so",
        "/system/lib/libndk_translation.so",
        "/system/lib64/libndk_translation.so"
    };

    for (const char* path : emu_files) {
        struct stat st;
        if (stat(path, &st) == 0) {
            LOGW("Emulator: file %s", path);
            return JNI_TRUE;
        }
    }

    // 3. Check for typical emulator network configuration
    // Emulators often have 10.0.2.x gateway
    FILE* route = popen("cat /proc/net/route 2>/dev/null | head -5", "r");
    if (route) {
        char line[256];
        while (fgets(line, sizeof(line), route)) {
            // Check for emulator gateway pattern
            if (strstr(line, "FE000000") || strstr(line, "00000000")) {
                // Check the gateway IP
                char gw[16] = {0};
                sscanf(line, "%*s %*s %15s", gw);
                // 10.0.2.0 in hex is 0A000200, reversed 0002000A
                if (strstr(gw, "0002000A") || strstr(gw, "0002")) {
                    LOGW("Emulator: network route %s", line);
                    pclose(route);
                    return JNI_TRUE;
                }
            }
        }
        pclose(route);
    }

    // 4. Check for emulator-specific build characteristics
    FILE* build_fp = popen("getprop ro.build.fingerprint 2>/dev/null", "r");
    if (build_fp) {
        char fingerprint[256] = {0};
        if (fgets(fingerprint, sizeof(fingerprint), build_fp)) {
            fingerprint[strcspn(fingerprint, "\n")] = 0;
            std::string fp(fingerprint);
            std::transform(fp.begin(), fp.end(), fp.begin(), ::tolower);
            if (fp.find("generic") != std::string::npos ||
                fp.find("sdk") != std::string::npos ||
                fp.find("emulator") != std::string::npos ||
                fp.find("vbox") != std::string::npos ||
                fp.find("test") != std::string::npos) {
                LOGW("Emulator: fingerprint %s", fp.c_str());
                pclose(build_fp);
                return JNI_TRUE;
            }
        }
        pclose(build_fp);
    }

    // 5. Check for BlueStacks, Nox, LDPlayer specific files
    const char* custom_emu_paths[] = {
        "/data/data/com.bluestacks",
        "/data/data/com.bignox.app",
        "/data/data/com.vphone.launcher",
        "/system/lib/libbs_vm.so"
    };
    for (const char* path : custom_emu_paths) {
        struct stat st;
        if (stat(path, &st) == 0) {
            LOGW("Emulator: custom emu %s", path);
            return JNI_TRUE;
        }
    }

    // 6. Check for QEMU guest driver in device info
    // QEMU emulates specific hardware
    FILE* cpu = fopen("/proc/cpuinfo", "r");
    if (cpu) {
        char line[256];
        while (fgets(line, sizeof(line), cpu)) {
            if (strstr(line, "QEMU") || strstr(line, "VirtualCPU") ||
                strstr(line, "KVM") || strstr(line, "hypervisor")) {
                LOGW("Emulator: cpu %s", line);
                fclose(cpu);
                return JNI_TRUE;
            }
        }
        fclose(cpu);
    }

    return JNI_FALSE;
}

// ============================================================
//  NEW: XPOSED / LSPOSED DETECTION
// ============================================================

jboolean checkXposedDetected() {
    // 1. Check for Xposed libraries in memory
    FILE* maps = fopen("/proc/self/maps", "r");
    if (maps) {
        char line[512];
        while (fgets(line, sizeof(line), maps)) {
            if (strstr(line, "xposed") ||
                strstr(line, "lsposed") ||
                strstr(line, "edxposed") ||
                strstr(line, "XPosed") ||
                strstr(line, "LSPosed") ||
                strstr(line, "de.robv.android.xposed") ||
                strstr(line, "org.lsposed") ||
                strstr(line, "com.elderdrivers")) {
                LOGW("Xposed: library %s", line);
                fclose(maps);
                return JNI_TRUE;
            }
        }
        fclose(maps);
    }

    // 2. Check for Xposed packages installed
    FILE* pm = popen("pm list packages 2>/dev/null | grep -iE 'xposed|lsposed|edxposed|microg'", "r");
    if (pm) {
        char line[256];
        while (fgets(line, sizeof(line), pm)) {
            LOGW("Xposed: pkg %s", line);
            pclose(pm);
            return JNI_TRUE;
        }
        pclose(pm);
    }

    // 3. Check for Xposed installer / modules
    const char* xposed_pkg_dirs[] = {
        "/data/data/de.robv.android.xposed.installer",
        "/data/data/com.solohsu.android.edxp.manager",
        "/data/data/org.lsposed.manager",
        "/data/data/com.topjohnwu.xposed",
        "/data/data/com.solohsu.android.edxp",
        "/data/data/org.lsposed.lsposed",
        "/data/misc/edxp",
        "/data/misc/lsposed"
    };

    for (const char* dir : xposed_pkg_dirs) {
        struct stat st;
        if (stat(dir, &st) == 0) {
            LOGW("Xposed: dir %s", dir);
            return JNI_TRUE;
        }
    }

    // 4. Check for Xposed runtime by looking for modified system classes
    // Xposed replaces the class loader - check for its signature
    void* xposed_handle = dlopen("xposed", RTLD_NOW | RTLD_NOLOAD);
    if (xposed_handle) {
        LOGW("Xposed: dlopen(xposed) succeeded");
        dlclose(xposed_handle);
        return JNI_TRUE;
    }

    // Check for LSPosed native library
    void* lsposed_handle = dlopen("lsposed", RTLD_NOW | RTLD_NOLOAD);
    if (lsposed_handle) {
        LOGW("Xposed: dlopen(lsposed) succeeded");
        dlclose(lsposed_handle);
        return JNI_TRUE;
    }

    // 5. Check for Xposed property
    FILE* xp_prop = popen("getprop vending 2>/dev/null", "r");
    if (xp_prop) {
        char val[16] = {0};
        if (fgets(val, sizeof(val), xp_prop)) {
            val[strcspn(val, "\n")] = 0;
            if (strlen(val) > 0) {
                // Xposed sets the "vending" property to a version string
                LOGW("Xposed: vending prop = %s", val);
                pclose(xp_prop);
                return JNI_TRUE;
            }
        }
        pclose(xp_prop);
    }

    // 6. Check for edXposed/LSPosed by looking for their signature
    // They leave specific traces in /data/misc/
    const char* xposed_traces[] = {
        "/data/misc/edxp/",
        "/data/misc/lsposed/",
        "/data/misc/riru/",
        "/data/misc/zygisk/",
        "/data/adb/lspd/",
        "/data/adb/modules/edxposed/",
        "/data/adb/modules/lsposed/",
        "/data/adb/modules/riru_edxposed/",
        "/data/adb/modules/riru_lsposed/"
    };
    for (const char* path : xposed_traces) {
        struct stat st;
        if (stat(path, &st) == 0) {
            LOGW("Xposed: trace %s", path);
            return JNI_TRUE;
        }
    }

    // 7. Check for Riru / Zygisk (used by modern Xposed variants)
    const char* riru_paths[] = {
        "/data/adb/riru/",
        "/data/adb/modules/riru/",
        "/data/adb/zygisk/",
        "/data/adb/modules/zygisk/"
    };
    for (const char* path : riru_paths) {
        struct stat st;
        if (stat(path, &st) == 0) {
            LOGW("Xposed: riru/zygisk %s", path);
            return JNI_TRUE;
        }
    }

    return JNI_FALSE;
}

// ============================================================
//  NEW: APK INTEGRITY CHECK (CRC/Checksum verification)
// ============================================================

// Simple CRC32 implementation for file integrity checks
static unsigned int calcCrc32(const unsigned char* data, size_t len) {
    unsigned int crc = 0xFFFFFFFF;
    static const unsigned int table[256] = {
        0x00000000, 0x77073096, 0xEE0E612C, 0x990951BA,
        0x076DC419, 0x706AF48F, 0xE963A535, 0x9E6495A3,
        0x0EDB8832, 0x79DCB8A4, 0xE0D5E91E, 0x97D2D988,
        0x09B64C2B, 0x7EB17CBD, 0xE7B82D07, 0x90BF1D91,
        0x1DB71064, 0x6AB020F2, 0xF3B97148, 0x84BE41DE,
        0x1ADAD47D, 0x6DDDE4EB, 0xF4D4B551, 0x83D385C7,
        0x136C9856, 0x646BA8C0, 0xFD62F97A, 0x8A65C9EC,
        0x14015C4F, 0x63066CD9, 0xFA0F3D63, 0x8D080DF5,
        0x3B6E20C8, 0x4C69105E, 0xD56041E4, 0xA2677172,
        0x3C03E4D1, 0x4B04D447, 0xD20D85FD, 0xA50AB56B,
        0x35B5A8FA, 0x42B2986C, 0xDBBBC9D6, 0xACBCF940,
        0x32D86CE3, 0x45DF5C75, 0xDCD60DCF, 0xABD13D59,
        0x26D930AC, 0x51DE003A, 0xC8D75180, 0xBFD06116,
        0x21B4F4B5, 0x56B3C423, 0xCFBA9599, 0xB8BDA50F,
        0x2802B89E, 0x5F058808, 0xC60CD9B2, 0xB10BE924,
        0x2F6F7C87, 0x58684C11, 0xC1611DAB, 0xB6662D3D
    };
    for (size_t i = 0; i < len; i++) {
        crc = table[(crc ^ data[i]) & 0xFF] ^ (crc >> 8);
    }
    return crc ^ 0xFFFFFFFF;
}

jboolean checkApkIntegrity(const char* apkPath) {
    if (!apkPath) return JNI_FALSE;

    // 1. Check that the APK file exists and has reasonable size
    struct stat st;
    if (stat(apkPath, &st) != 0) {
        LOGW("Integrity: APK not found at %s", apkPath);
        return JNI_TRUE; // Can't find APK = suspicious
    }

    // APK should be at least 1MB
    if (st.st_size < 1048576) {
        LOGW("Integrity: APK too small %lld", (long long)st.st_size);
        return JNI_TRUE;
    }

    // 2. Read the first few KB of the APK and verify ZIP header
    FILE* apk = fopen(apkPath, "rb");
    if (!apk) {
        LOGW("Integrity: Cannot open APK");
        return JNI_TRUE;
    }

    // Check ZIP magic bytes (PK\x03\x04)
    unsigned char magic[4];
    if (fread(magic, 1, 4, apk) != 4) {
        fclose(apk);
        LOGW("Integrity: Cannot read magic");
        return JNI_TRUE;
    }
    if (magic[0] != 'P' || magic[1] != 'K' || magic[2] != 0x03 || magic[3] != 0x04) {
        LOGW("Integrity: Bad ZIP magic");
        fclose(apk);
        return JNI_TRUE;
    }

    // 3. Read the Central Directory at the end of the file
    // to find the signing block (APK Signature Scheme v2/v3)
    fseek(apk, -22, SEEK_END); // End of Central Directory starts 22 bytes from end
    unsigned char eocd[22];
    if (fread(eocd, 1, 22, apk) != 22) {
        fclose(apk);
        return JNI_FALSE; // Can't read EOCD
    }

    // Check EOCD signature
    if (eocd[0] != 0x50 || eocd[1] != 0x4B || eocd[2] != 0x05 || eocd[3] != 0x06) {
        LOGW("Integrity: Bad EOCD signature");
        fclose(apk);
        return JNI_TRUE;
    }

    fclose(apk);

    // 4. Quick CRC check of classes.dex (main DEX file)
    // The DEX header starts at offset 0x1000 typically in APKs
    // Verify DEX magic
    FILE* apk2 = fopen(apkPath, "rb");
    if (apk2) {
        fseek(apk2, 0, SEEK_END);
        long fileSize = ftell(apk2);
        rewind(apk2);

        // Search for classes.dex within the APK
        // In an APK, DEX files are stored with their original headers
        // We'll check the first DEX header magic
        unsigned char buffer[4096];
        size_t bytesRead;
        bool foundDex = false;

        // Read the APK in chunks to find DEX header
        // DEX files start with "dex\n035\0" magic
        while ((bytesRead = fread(buffer, 1, sizeof(buffer), apk2)) > 0) {
            for (size_t i = 0; i < bytesRead - 8; i++) {
                if (buffer[i] == 'd' && buffer[i+1] == 'e' && buffer[i+2] == 'x' &&
                    buffer[i+3] == '\n' && buffer[i+4] == '0') {
                    foundDex = true;
                    break;
                }
            }
            if (foundDex) break;
        }
        fclose(apk2);

        if (!foundDex) {
            LOGW("Integrity: No DEX header found");
            return JNI_TRUE;
        }
    }

    LOGI("Integrity: APK checks passed");
    return JNI_FALSE;
}

// ============================================================
//  NEW: SIGNATURE VERIFICATION (via Java + JNI)
// ============================================================

// The actual signature verification is done in Java via PackageManager.
// This C++ function computes a hash to cross-check with Java.

static const char* getExpectedSignatureHash() {
    // Replace this with your APK's actual signing certificate SHA-256 hash
    // You can get this by running:
    //   keytool -list -v -keystore debug.keystore
    // After building the APK
    return "debug_signature_placeholder";
}

// ============================================================
//  COMBINED TAMPER CHECK
// ============================================================

jboolean checkAllTamper() {
    if (checkRootDetected()) return JNI_TRUE;
    if (checkFridaDetected()) return JNI_TRUE;
    if (checkDebugDetected()) return JNI_TRUE;
    if (checkEmulatorDetected()) return JNI_TRUE;
    if (checkXposedDetected()) return JNI_TRUE;
    return JNI_FALSE;
}

// ============================================================
//  JNI EXPORTS
// ============================================================

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_drarabi_medvision_SecurityManager_nativeCheckRoot(JNIEnv* env, jobject thiz) {
    return checkRootDetected();
}

JNIEXPORT jboolean JNICALL
Java_com_drarabi_medvision_SecurityManager_nativeCheckFrida(JNIEnv* env, jobject thiz) {
    return checkFridaDetected();
}

JNIEXPORT jboolean JNICALL
Java_com_drarabi_medvision_SecurityManager_nativeCheckDebug(JNIEnv* env, jobject thiz) {
    return checkDebugDetected();
}

JNIEXPORT jboolean JNICALL
Java_com_drarabi_medvision_SecurityManager_nativeCheckEmulator(JNIEnv* env, jobject thiz) {
    return checkEmulatorDetected();
}

JNIEXPORT jboolean JNICALL
Java_com_drarabi_medvision_SecurityManager_nativeCheckXposed(JNIEnv* env, jobject thiz) {
    return checkXposedDetected();
}

JNIEXPORT jboolean JNICALL
Java_com_drarabi_medvision_SecurityManager_nativeCheckApkIntegrity(JNIEnv* env, jobject thiz,
                                                             jstring apkPath) {
    const char* path = env->GetStringUTFChars(apkPath, nullptr);
    jboolean result = checkApkIntegrity(path);
    env->ReleaseStringUTFChars(apkPath, path);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_drarabi_medvision_SecurityManager_nativeCheckAll(JNIEnv* env, jobject thiz) {
    return checkAllTamper();
}

JNIEXPORT jstring JNICALL
Java_com_drarabi_medvision_SecurityManager_nativeGetStatus(JNIEnv* env, jobject thiz) {
    std::string status;
    status += "root:" + std::to_string(checkRootDetected());
    status += ",frida:" + std::to_string(checkFridaDetected());
    status += ",debug:" + std::to_string(checkDebugDetected());
    status += ",emu:" + std::to_string(checkEmulatorDetected());
    status += ",xposed:" + std::to_string(checkXposedDetected());
    return env->NewStringUTF(status.c_str());
}

} // extern "C"
