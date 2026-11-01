package com.shegrowsdev.mail.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.shegrowsdev.mail.R

/** The company logo on a white card (its black lettering needs a light background, also in dark mode). */
@Composable
fun BrandLogo(height: Dp, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxWidth(), color = androidx.compose.ui.graphics.Color.White, shape = RoundedCornerShape(16.dp)) {
        Image(
            painter = painterResource(R.drawable.logo_full),
            contentDescription = stringResource(R.string.app_name),
            contentScale = ContentScale.Fit,
            modifier = Modifier.height(height).fillMaxWidth().padding(8.dp)
        )
    }
}
