package com.footprints.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "points")
data class PointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val lat: Double,
    val lng: Double,
    val accuracy: Float,
    val time: Long,
    /** 与上一个轨迹点的地面距离（米），首点为 0。用于 O(1) 汇总总里程 */
    val distPrev: Double = 0.0,
    /** 记录时刻的逆地理编码地址（v3 起新增，旧数据为空串显示坐标） */
    @ColumnInfo(defaultValue = "") val address: String = "",
)
