package com.daydreamvr.vrcore.optics
import com.google.common.truth.Truth.assertThat
import org.junit.Test
class OpticsGeometryTest {
    private fun fixture(divider: Int = 0) = OpticsGeometry.compute(DisplayGeometry(.140, .070, 2000, 1000), ViewerOptics("fixture", lensSeparationM=.064, screenToLensM=.040, coefficients=RadialCoefficients(0.0,0.0), maxFov=MaxFov(80.0,80.0,80.0,80.0), dividerPx=divider), ObserverGeometry())
    @Test fun asymmetricPhysicalFixtureIsExact() { val s=fixture(); assertThat(s.left.lensCenterPanelM.x).isWithin(1e-12).of(.038); assertThat(s.left.lensCenterPanelM.y).isWithin(1e-12).of(.035); assertThat(s.right.lensCenterPanelM.x).isWithin(1e-12).of(.102); assertThat(s.left.sourceBounds.left).isWithin(1e-12).of(-.95); assertThat(s.left.sourceBounds.right).isWithin(1e-12).of(.8); assertThat(s.right.sourceBounds.left).isWithin(1e-12).of(-.8); assertThat(s.right.sourceBounds.right).isWithin(1e-12).of(.95) }
    @Test fun dividerAndObserverIpdDoNotMoveFixedOptics() { val a=fixture(8); assertThat(a.left.viewport.width).isEqualTo(996); assertThat(a.right.viewport.x).isEqualTo(1004); val b=OpticsGeometry.compute(DisplayGeometry(.140,.070,2000,1000), ViewerOptics("fixture",lensSeparationM=.064,screenToLensM=.040,coefficients=RadialCoefficients(0.0,0.0),maxFov=MaxFov(80.0,80.0,80.0,80.0),dividerPx=8),ObserverGeometry(.074)); assertThat(a.geometryKey).isEqualTo(b.geometryKey) }
}
