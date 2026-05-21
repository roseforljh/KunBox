package com.kunk.singbox.database

import androidx.room.TypeConverter
import com.kunk.singbox.model.ProfileType
import com.kunk.singbox.model.UpdateStatus

/**
 *
 *
 */
class Converters {

    @TypeConverter
    fun fromProfileType(value: ProfileType): String = value.name

    @TypeConverter
    fun toProfileType(value: String): ProfileType =
        runCatching { ProfileType.valueOf(value) }.getOrDefault(ProfileType.Subscription)

    @TypeConverter
    fun fromUpdateStatus(value: UpdateStatus): String = value.name

    @TypeConverter
    fun toUpdateStatus(value: String): UpdateStatus =
        runCatching { UpdateStatus.valueOf(value) }.getOrDefault(UpdateStatus.Idle)
}
