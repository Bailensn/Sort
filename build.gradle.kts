// AGP 9 自带「内置 Kotlin」，随插件携带的 KGP 是 2.2.10。
// 想把 Kotlin 编译器升到 2.4.21，官方做法是把更高版本的 KGP 放到
// 根工程的 buildscript classpath 上，AGP 会以它为准（版本冲突取高者）。
// 注意：不要再应用 org.jetbrains.kotlin.android，内置 Kotlin 会拒绝它。
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // 与 gradle/libs.versions.toml 里的 kotlin 保持一致。
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
