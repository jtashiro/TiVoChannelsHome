import java.util.concurrent.TimeUnit

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.fiospace.tivochannelshome"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.fiospace.tivochannelshome"
        minSdk = 21
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.leanback)
    implementation(libs.glide)
}

// Ensure that when the debug APK is assembled, we automatically install it to the connected device.
// Use finalizedBy so we don't create a circular dependency (installDebug usually depends on assembleDebug).
gradle.projectsEvaluated {
    tasks.matching { it.name == "assembleDebug" }.configureEach {
        // run installDebug after assembleDebug finishes
        finalizedBy("installDebug")
    }
}

// Add a debug helper task to uninstall OEM apps via adb after installing the debug APK.
// Usage: ./gradlew :app:installDebug (the uninstall task will run automatically after installDebug)
// You can override the per-package timeout by passing -PuninstallTimeoutMs=60000 (milliseconds)
gradle.projectsEvaluated {
    tasks.register("uninstallOemApps") {
        group = "installation"
        description = "Uninstall known OEM packages (pm uninstall --user 0) from the connected device(s)."

        doLast {
            val envSerial = System.getenv("ANDROID_SERIAL")
            val serialArg = if (!envSerial.isNullOrBlank()) arrayOf("-s", envSerial) else arrayOf<String>()

            // configurable timeout per package via -PuninstallTimeoutMs (ms)
            val defaultTimeout = 120_000L
            val timeoutMs = try {
                (project.findProperty("uninstallTimeoutMs") as String?)?.toLong() ?: defaultTimeout
            } catch (e: Exception) {
                defaultTimeout
            }

            val targets = listOf(
                "com.tivo.atom",
                "com.amazon.freevee",
                "com.sling",
                "com.amazon.amazonvideo.livingroom",
                "com.imdbtv.livingroom",
                "com.amazon.avod",
                "com.google.android.play.games",
                "com.google.android.videos",
                "com.google.android.youtube.tvmusic",
                "com.plexapp.android",
                "ar.tvplayer.tv",
                "com.wolf.firelauncher",
                "com.bydeluxe.d3.android.program.starz",
                "com.netflix.ninja"
            )

            println("[uninstallOemApps] ANDROID_SERIAL=${envSerial ?: "(none)"}")
            println("[uninstallOemApps] Targets: ${targets.joinToString(", ")}")
            println("[uninstallOemApps] Per-target timeout: ${timeoutMs}ms")

            targets.forEach { pkg ->
                println("[uninstallOemApps] -> Running: adb ${serialArg.joinToString(" ")} shell pm uninstall --user 0 $pkg")

                try {
                    // Build command list
                    val cmd = mutableListOf<String>()
                    cmd.add("adb")
                    if (serialArg.isNotEmpty()) cmd.addAll(serialArg)
                    cmd.addAll(listOf("shell", "pm", "uninstall", "--user", "0", pkg))

                    val pb = ProcessBuilder(cmd)
                    pb.redirectErrorStream(true)
                    val proc = pb.start()

                    // Read output asynchronously
                    val outThread = Thread {
                        try {
                            proc.inputStream.bufferedReader().use { rd ->
                                var line: String? = rd.readLine()
                                while (line != null) {
                                    println("[uninstallOemApps][$pkg][out] $line")
                                    line = rd.readLine()
                                }
                            }
                        } catch (e: Exception) {
                            println("[uninstallOemApps][$pkg][out] error reading stream: ${e.message}")
                        }
                    }
                    outThread.start()

                    val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                    if (!finished) {
                        println("[uninstallOemApps][$pkg] timed out after ${timeoutMs}ms -> destroying process")
                        proc.destroyForcibly()
                        outThread.join(2000)
                        println("[uninstallOemApps][$pkg] timeout (killed)")
                    } else {
                        val exit = proc.exitValue()
                        outThread.join(2000)
                        println("[uninstallOemApps][$pkg] exited with code=$exit")
                        if (exit == 0) {
                            println("[uninstallOemApps][$pkg] uninstall reported success (exit=0)")
                        } else {
                            println("[uninstallOemApps][$pkg] uninstall returned non-zero (exit=$exit)")
                        }
                    }
                } catch (e: Exception) {
                    println("[uninstallOemApps][$pkg] Exception: ${e.message}")
                }

                // small pause between packages
                try { Thread.sleep(500) } catch (_: Exception) {}
            }

            println("[uninstallOemApps] Completed all targets")
        }
    }

    // Ensure the uninstall task runs after installDebug completes (so the device has the new APK installed first)
    tasks.matching { it.name == "installDebug" }.configureEach {
        finalizedBy("uninstallOemApps")
    }
}
