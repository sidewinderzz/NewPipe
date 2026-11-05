package org.schabi.newpipe.subspipe

import android.view.View
import com.xwray.groupie.viewbinding.BindableItem
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.SubspipeSubscriptionsEmptyBinding

/**
 * SubsPipe: shown in the Subscriptions tab while nothing is imported, with buttons to import
 * the Takeout CSV right away or to search for channels.
 */
class EmptySubscriptionsItem(
    private val onImportCsv: () -> Unit,
    private val onFindChannels: () -> Unit
) : BindableItem<SubspipeSubscriptionsEmptyBinding>() {
    override fun getLayout(): Int = R.layout.subspipe_subscriptions_empty

    override fun bind(viewBinding: SubspipeSubscriptionsEmptyBinding, position: Int) {
        viewBinding.emptyImportCsv.setOnClickListener { onImportCsv() }
        viewBinding.emptyFindChannels.setOnClickListener { onFindChannels() }
    }

    override fun getSpanSize(spanCount: Int, position: Int): Int = spanCount

    override fun initializeViewBinding(view: View) = SubspipeSubscriptionsEmptyBinding.bind(view)
}
