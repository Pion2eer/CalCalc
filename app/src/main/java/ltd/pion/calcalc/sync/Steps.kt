package ltd.pion.calcalc.sync

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.LocalDate
import java.time.ZoneId

enum class StepsState { UNAVAILABLE, NEEDS_INSTALL, OFF, ON }

/** Steps from Health Connect, where Google Fit, Samsung Health and the phone's own step counter write them. */
object Steps {
    const val PROVIDER = "com.google.android.apps.healthdata"
    val permissions = setOf(HealthPermission.getReadPermission(StepsRecord::class))

    suspend fun state(context: Context): StepsState = when (HealthConnectClient.getSdkStatus(context, PROVIDER)) {
        HealthConnectClient.SDK_AVAILABLE ->
            if (HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions().containsAll(permissions)) StepsState.ON
            else StepsState.OFF
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> StepsState.NEEDS_INSTALL
        else -> StepsState.UNAVAILABLE
    }

    suspend fun count(context: Context, day: LocalDate): Long {
        val zone = ZoneId.systemDefault()
        val r = HealthConnectClient.getOrCreate(context).aggregate(
            AggregateRequest(
                setOf(StepsRecord.COUNT_TOTAL),
                TimeRangeFilter.between(day.atStartOfDay(zone).toInstant(), day.plusDays(1).atStartOfDay(zone).toInstant()),
            )
        )
        return r[StepsRecord.COUNT_TOTAL] ?: 0
    }
}
