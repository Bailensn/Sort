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

// 根工程声明过的插件，子工程才能用「带版本」的方式再申请一次：
// buildscript 的依赖会让插件 classpath 在配置阶段就先于 plugins 块就绪，
// 此时子工程若申请一个根工程没声明过的插件 id，Gradle 会报
// “plugin is already on the classpath with an unknown version”。
// 所以这里把每个用到的插件都声明一遍（apply false 只上 classpath、不应用）。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
