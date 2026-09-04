package com.footprints.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.footprints.app.data.PointEntity
import com.footprints.app.databinding.ItemLogPointBinding
import com.footprints.app.util.Format
import java.util.Locale

/**
 * 轨迹日志列表：一行一个轨迹点（时间 + 记录时刻的地址）。
 * 旧数据无地址时回退显示坐标与精度。
 */
class LogPointAdapter(
    private val items: List<PointEntity>,
    private val onClick: (PointEntity) -> Unit,
) : RecyclerView.Adapter<LogPointAdapter.VH>() {

    inner class VH(val binding: ItemLogPointBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemLogPointBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = items[position]
        with(holder.binding) {
            tvTime.text = Format.pointTime(p.time)
            tvAddr.text = p.address.ifBlank {
                String.format(Locale.US, "%.5f, %.5f · 精度 %d 米", p.lat, p.lng, p.accuracy.toInt())
            }
            root.setOnClickListener { onClick(p) }
        }
    }
}
