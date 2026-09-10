package com.daydreamvr.vrcore.optics

import org.junit.Assert.assertThrows
import org.junit.Test

class OpticsValidationTest {

    private val validDisplay = DisplayGeometry(
        panelWidthM = 0.140,
        panelHeightM = 0.070,
        surfaceWidthPx = 2000,
        surfaceHeightPx = 1000,
    )

    private val validViewer = ViewerOptics(
        profileId = "test",
        lensSeparationM = 0.064,
        screenToLensM = 0.040,
        coefficients = RadialCoefficients(0.34, 0.55),
        maxFov = MaxFov(40.0, 40.0, 40.0, 40.0),
        dividerPx = 8,
    )

    private val validObserver = ObserverGeometry(0.064)

    @Test
    fun zeroOrNegativeOrNanOrInfinitePanelDimensionsThrow() {
        assertThrows(OpticsValidationException::class.java) {
            OpticsGeometry.compute(validDisplay.copy(panelWidthM = 0.0), validViewer, validObserver)
        }
        assertThrows(OpticsValidationException::class.java) {
            OpticsGeometry.compute(validDisplay.copy(panelWidthM = -0.14), validViewer, validObserver)
        }
        assertThrows(OpticsValidationException::class.java) {
            OpticsGeometry.compute(validDisplay.copy(panelWidthM = Double.NaN), validViewer, validObserver)
        }
        assertThrows(OpticsValidationException::class.java) {
            OpticsGeometry.compute(validDisplay.copy(panelHeightM = Double.POSITIVE_INFINITY), validViewer, validObserver)
        }
    }

    @Test
    fun panelDimensionsOutsideValidatedBoundsThrow() {
        // [0.08, 0.20] for W
        assertThrows(OpticsValidationException::class.java) {
            OpticsGeometry.compute(validDisplay.copy(panelWidthM = 0.079), validViewer, validObserver)
        }
        assertThrows(OpticsValidationException::class.java) {
            OpticsGeometry.compute(validDisplay.copy(panelWidthM = 0.201), validViewer, validObserver)
        }
        // [0.03, 0.12] for H
        assertThrows(OpticsValidationException::class.java) {
            OpticsGeometry.compute(validDisplay.copy(panelHeightM = 0.029), validViewer, validObserver)
        }
        assertThrows(OpticsValidationException::class.java) {
            OpticsGeometry.compute(validDisplay.copy(panelHeightM = 0.121), validViewer, validObserver)
        }
    }

    @Test
    fun screenToLensDistanceOutsideValidatedBoundsThrows() {
        // [0.030, 0.060]
        assertThrows(OpticsValidationException::class.java) {
            OpticsGeometry.compute(validDisplay, validViewer.copy(screenToLensM = 0.029), validObserver)
        }
        assertThrows(OpticsValidationException::class.java) {
            OpticsGeometry.compute(validDisplay, validViewer.copy(screenToLensM = 0.061), validObserver)
        }
    }

    @Test
    fun coefficientsOutsideZeroToOneThrow() {
        assertThrows(IllegalArgumentException::class.java) {
            RadialCoefficients(-0.01, 0.5)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RadialCoefficients(0.3, 1.05)
        }
    }

    @Test
    fun fovAnglesZeroOrGte89Throw() {
        assertThrows(IllegalArgumentException::class.java) {
            MaxFov(outer = 0.0, inner = 40.0, up = 40.0, down = 40.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MaxFov(outer = 89.0, inner = 40.0, up = 40.0, down = 40.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MaxFov(outer = 95.0, inner = 40.0, up = 40.0, down = 40.0)
        }
    }

    @Test
    fun excessiveDividerOrInsetsThrow() {
        // dividerPx in [0, 40]
        assertThrows(OpticsValidationException::class.java) {
            OpticsGeometry.compute(validDisplay, validViewer.copy(dividerPx = 42), validObserver)
        }
        // Insets larger than half surface width -> empty viewport
        assertThrows(OpticsValidationException::class.java) {
            val bigInsets = PixelInsets(left = 1200)
            OpticsGeometry.compute(validDisplay.copy(usableInsets = bigInsets), validViewer, validObserver)
        }
    }

    @Test
    fun lensCenterOutsideViewportThrows() {
        // Mount offset pushes lens center outside the left eye viewport
        val shiftedViewer = validViewer.copy(horizontalOffsetM = -0.050)
        assertThrows(OpticsValidationException::class.java) {
            OpticsGeometry.compute(validDisplay, shiftedViewer, validObserver)
        }
    }

    @Test
    fun bottomAlignmentWithoutMeasuredOffsetThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            ViewerOptics(
                profileId = "bottom",
                lensSeparationM = 0.064,
                screenToLensM = 0.040,
                verticalAlignment = VerticalAlignment.BOTTOM,
                trayToActiveBottomM = null,
                coefficients = RadialCoefficients(0.34, 0.55),
                maxFov = MaxFov(40.0, 40.0, 40.0, 40.0),
            )
        }
    }
}
