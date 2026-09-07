allprojects {
    repositories {
        mavenCentral()
        google()
        maven("https://jitpack.io") {
            content {
                includeModule("com.github.ankidroid", "Anki-Android")
            }
        }
        mavenLocal {
            content {
                includeVersionByRegex(JellyfinSdk.GROUP, ".*", JellyfinSdk.LOCAL)
            }
        }
        maven("https://s01.oss.sonatype.org/content/repositories/snapshots/") {
            content {
                includeVersionByRegex(JellyfinSdk.GROUP, ".*", JellyfinSdk.SNAPSHOT)
                includeVersionByRegex(JellyfinSdk.GROUP, ".*", JellyfinSdk.SNAPSHOT_UNSTABLE)
                includeVersionByRegex(JellyfinMedia3.GROUP, ".*", JellyfinMedia3.SNAPSHOT)
            }
        }
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
