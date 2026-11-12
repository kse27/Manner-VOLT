package com.mannervolt

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.mannervolt.backend.DemoConfig
import com.mannervolt.ui.MannerVoltApp

class MainActivity : ComponentActivity() {
    private val vm: AppController by viewModels()

    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { vm.onLocationPermissionResult() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 국내 서비스: 시각은 항상 한국 시간으로 보여 준다 (에뮬레이터 기본 시간대가 달라도 두 화면의 시계가 같게)
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Seoul"))
        // 두 대 시연(영상 촬영용) 설정은 ViewModel을 만들기 전에 읽는다
        DemoConfig.load(this, intent)
        setContent { MannerVoltApp(vm) }
        // 충전 구역 이탈 판단용 (실제 차량 데이터 모드). 거부해도 시뮬레이터 모드는 동작한다.
        locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}
