package com.yunpan.tingshu.ui.me

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.yunpan.tingshu.data.model.CloudAccount
import com.yunpan.tingshu.databinding.ItemAccountBinding
import com.yunpan.tingshu.ui.common.Fmt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 账号列表适配器：展示手机号/到期、选中态，回调切换/删除。 */
class AccountAdapter(
    private val onSetActive: (CloudAccount) -> Unit,
    private val onRemove: (CloudAccount) -> Unit
) : RecyclerView.Adapter<AccountAdapter.VH>() {

    private val items = mutableListOf<CloudAccount>()
    private var activeId: String? = null

    fun submit(list: List<CloudAccount>, activeId: String?) {
        this.items.clear()
        this.items.addAll(list)
        this.activeId = activeId
        notifyDataSetChanged()
    }

    inner class VH(val binding: ItemAccountBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemAccountBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val acc = items[position]
        val b = holder.binding
        b.accountName.text = acc.phone.ifEmpty { acc.displayName }

        val now = System.currentTimeMillis()
        val daysLeft = if (acc.expireAtMs > 0) ((acc.expireAtMs - now) / (24 * 3600 * 1000)).toInt() else -1

        b.accountExpire.text = if (acc.expireAtMs > 0) {
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(acc.expireAtMs))
            when {
                acc.isExpired -> "已过期（$date）"
                daysLeft in 0..7 -> "${daysLeft}天后到期（$date）"
                else -> "到期 $date"
            }
        } else {
            "到期时间未知"
        }

        // 颜色分级：已过期=红，7天内到期=橙，正常=灰
        val colorRes = when {
            acc.isExpired -> com.yunpan.tingshu.R.color.error
            daysLeft in 0..7 -> android.R.color.holo_orange_dark
            else -> com.yunpan.tingshu.R.color.onSurfaceVariant
        }
        b.accountExpire.setTextColor(
            androidx.core.content.ContextCompat.getColor(b.root.context, colorRes)
        )

        b.accountActiveBadge.visibility = if (acc.id == activeId) android.view.View.VISIBLE else android.view.View.GONE
        b.btnSetActive.visibility = if (acc.id == activeId) android.view.View.GONE else android.view.View.VISIBLE
        b.btnSetActive.setOnClickListener { onSetActive(acc) }
        b.btnRemoveAccount.setOnClickListener { onRemove(acc) }
    }

    override fun getItemCount(): Int = items.size
}
