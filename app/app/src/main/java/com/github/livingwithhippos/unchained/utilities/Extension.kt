package com.github.livingwithhippos.unchained.utilities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Animatable
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.RotateDrawable
import android.graphics.drawable.ScaleDrawable
import android.graphics.drawable.VectorDrawable
import android.net.Uri
import android.util.Patterns
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.databinding.BindingAdapter
import androidx.fragment.app.Fragment
import com.bumptech.glide.request.RequestOptions.bitmapTransform
import com.bumptech.glide.request.target.CustomViewTarget
import com.bumptech.glide.request.transition.Transition
import com.github.livingwithhippos.unchained.R
import com.google.android.material.progressindicator.ProgressIndicator

//todo: split extensions in own files (glide/views etc)
@BindingAdapter("imageURL")
fun ImageView.loadImage(imageURL: String?) {
    if (imageURL != null)
        GlideApp.with(this.context)
            .load(imageURL)
            .into(this)
}

@BindingAdapter("startAnimation")
fun ImageView.startAnimation(start: Boolean) {
    if (drawable is Animatable) {
        if (start)
            (drawable as Animatable).start()
        else
            (drawable as Animatable).stop()
    }
}

@BindingAdapter("blurredBackground")
fun ConstraintLayout.blurredBackground(drawable: Drawable) {
    val layout = this
    GlideApp.with(this)
        .load(drawable)
        .apply(bitmapTransform(BlurTransformation(context)))
        .into(object : CustomViewTarget<ConstraintLayout, Drawable>(layout) {
            override fun onLoadFailed(errorDrawable: Drawable?) {
                // error handling
            }

            override fun onResourceCleared(placeholder: Drawable?) {
                // clear all resources
            }

            override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                layout.background = resource
            }
        })
}

@BindingAdapter("adapter")
fun AutoCompleteTextView.setAdapter(contents: List<String>) {
    // a simple layout is set for the dropdown items
    val adapter = ArrayAdapter<String>(this.context, R.layout.dropdown_plain_item, contents)
    this.setAdapter(adapter)
}

@BindingAdapter("backgroundProgressColor")
fun ProgressBar.setBackgroundProgressColor(color: Int) {
    tintDrawable(android.R.id.background, color)
}

@BindingAdapter("progressColor")
fun ProgressBar.setProgressColor(color: Int) {
    tintDrawable(android.R.id.progress, color)
}

@BindingAdapter("secondaryProgressColor")
fun ProgressBar.setSecondaryProgressColor(color: Int) {
    tintDrawable(android.R.id.secondaryProgress, color)
}

@BindingAdapter("backgroundProgressDrawable")
fun ProgressBar.setBackgroundProgressDrawable(drawable: Drawable) {
    swapLayerDrawable(android.R.id.background, drawable)
}

@BindingAdapter("primaryProgressDrawable")
fun ProgressBar.setPrimaryProgressDrawable(drawable: Drawable) {
    swapLayerDrawable(android.R.id.progress, drawable)
}

@BindingAdapter("secondaryProgressDrawable")
fun ProgressBar.setSecondaryProgressDrawable(drawable: Drawable) {
    swapLayerDrawable(android.R.id.secondaryProgress, drawable)
}

fun ProgressBar.tintDrawable(layerId: Int, color: Int) {
    val progressDrawable = getDrawableByLayerId(layerId).mutate()
    progressDrawable.setTint(color)
}

fun ProgressBar.swapLayerDrawable(layerId: Int, drawable: Drawable) {
    when (val oldDrawable = getDrawableByLayerId(layerId)) {
        is ClipDrawable -> oldDrawable.drawable = drawable
        is ScaleDrawable -> oldDrawable.drawable = drawable
        is InsetDrawable -> oldDrawable.drawable = drawable
        is RotateDrawable -> oldDrawable.drawable = drawable
        is VectorDrawable -> getLayerDrawable().setDrawableByLayerId(layerId, drawable)
        // ShapeDrawable is a generic shape and does not have drawables
        // is ShapeDrawable ->
    }
}

fun ProgressBar.getLayerDrawable(): LayerDrawable {
    return (if (isIndeterminate) indeterminateDrawable else progressDrawable) as LayerDrawable
}

fun ProgressBar.getDrawableByLayerId(id: Int): Drawable {
    return getLayerDrawable().findDrawableByLayerId(id)
}

@BindingAdapter("progressCompat")
fun ProgressIndicator.setRealProgress(progress: Int) {
    val animated: Boolean = true
    this.setProgressCompat(progress, animated)
}

fun View.runRippleAnimation(){
    //todo: test
    if (background is RippleDrawable) {
        postDelayed(
            Runnable {
                background.state = intArrayOf(
                    android.R.attr.state_pressed,
                    android.R.attr.state_enabled
                )
            },
            300
        )
    }
}

fun Fragment.showToast(stringResource: Int, length: Int = Toast.LENGTH_SHORT) {
    Toast.makeText(requireContext(), getString(stringResource), length).show()
}

// note: should this be added to Context instead of Fragment?
fun Fragment.copyToClipboard(label: String, text: String) {
    val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip: ClipData = ClipData.newPlainText(label, text)
    // Set the clipboard's primary clip.
    clipboard.setPrimaryClip(clip)
}

fun Fragment.openExternalWebPage(url: String, showErrorToast: Boolean = true): Boolean {
    // this pattern accepts everything that is something.tld since there were too many new tlds and Google gave up updating their regex
    if (Patterns.WEB_URL.matcher(url).matches()) {
        val webIntent: Intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        startActivity(webIntent)
        return true
    } else
        if (showErrorToast)
            showToast(R.string.invalid_url)

    return false
}