import java.util.Properties

// 백엔드 주소는 local.properties 또는 환경변수에서 읽는다. 비밀 값은 앱에 넣지 않는다 (SECURITY-12).
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun prop(name: String): String = (localProps.getProperty(name) ?: System.getenv(name) ?: "").trim()

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.mannervolt"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mannervolt"
        minSdk = 28
        targetSdk = 34
        versionCode = 3
        versionName = "0.3.0"
        // 비어 있으면 앱 내부 시연 백엔드(LocalBackend)를 사용한다.
        buildConfigField("String", "API_BASE_URL", "\"${prop("MANNERVOLT_API_BASE_URL")}\"")
        // 지도 타일: 브이월드(국토교통부 공간정보 오픈플랫폼) 인증키. 비어 있으면 OpenStreetMap(CARTO) 타일을 쓴다.
        // 브이월드 키는 웹 지도처럼 클라이언트에서 쓰는 공개용 키이며, local.properties(깃 제외)에만 둔다.
        buildConfigField("String", "VWORLD_API_KEY", "\"${prop("VWORLD_API_KEY")}\"")
        buildConfigField("String", "VWORLD_DOMAIN", "\"${prop("VWORLD_DOMAIN")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 공모전 제출용 설치 가능 APK: 디버그 키로 서명 (실서비스 배포 아님)
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    androidResources { localeFilters += listOf("ko") }
    sourceSets {
        // 공통 정책 파일 (config/policy.json)을 assets로 포함
        getByName("main").assets.srcDirs("../config")
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    // Pleos Vehicle SDK (EV 배터리, 충전구, 기어)
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    // 내비 도착 예정 시간 (FR-11) — 지도 화면은 기본 내비를 그대로 쓰고 정보만 받는다
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    // Gleo AI 음성 안내 (FR-13.2) — Pleos에는 표준 android TextToSpeech 엔진이 없다
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    // 실제 지도 (Google Play 서비스 없이 동작하는 타일 지도: 브이월드 / OpenStreetMap)
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    // PBT-09: Kotest Property Testing + JUnit4
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.kotest:kotest-property-jvm:5.9.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
