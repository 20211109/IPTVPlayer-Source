package com.iptv.player.ui

import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.Size

/**
 * 扫码页：把 zxing 的默认全屏取景区收成一个居中的正方形。
 *
 * 默认实现里解码框是「整块预览画面去掉 10% 边距」，画面可解码面积越大，
 * 二维码在送进解码器的图里占的像素比就越小；稍远一点、屏上有反光、
 * 或者码印得密一些，就很容易解不出来，表现为「镜头对着半天没反应」。
 *
 * 收窄解码框有两个直接好处：
 * 1. 解码只在框内那块区域做，码在裁剪后的图里占比更大，识别更快更稳；
 * 2. 屏幕上的取景框（ViewfinderView）同步变小，用户会自然把手机凑近、
 *    把码填满框，等于「手动光学变焦」，识别距离和成功率都会好一截。
 *
 * 只改取景/解码区域，拍照、对焦、返回结果仍全部走 zxing 原逻辑。
 */
class QrScanActivity : CaptureActivity() {

    protected override fun initializeContent(): DecoratedBarcodeView {
        val view = super.initializeContent()
        val dm = resources.displayMetrics
        // 取屏幕短边的 62% 作为正方形边长：竖屏手机约 240dp，横屏平板按高度算
        val shorter = if (dm.widthPixels < dm.heightPixels) dm.widthPixels else dm.heightPixels
        val side = (shorter * 0.62f).toInt()
        if (side > 0) {
            view.barcodeView.setFramingRectSize(Size(side, side))
        }
        return view
    }
}
