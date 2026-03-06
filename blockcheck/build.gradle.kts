plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    linuxX64 {
        binaries { executable { entryPoint = "systems.greynet.blockcheck.main" } }
    }
    linuxArm64 {
        binaries { executable { entryPoint = "systems.greynet.blockcheck.main" } }
    }
    androidNativeArm64 {
        binaries { executable { entryPoint = "systems.greynet.blockcheck.main" } }
    }
    androidNativeArm32 {
        binaries { executable { entryPoint = "systems.greynet.blockcheck.main" } }
    }
    androidNativeX64 {
        binaries { executable { entryPoint = "systems.greynet.blockcheck.main" } }
    }
    androidNativeX86 {
        binaries { executable { entryPoint = "systems.greynet.blockcheck.main" } }
    }
    mingwX64 {
        binaries { executable { entryPoint = "systems.greynet.blockcheck.main" } }
    }

    applyDefaultHierarchyTemplate()
}
