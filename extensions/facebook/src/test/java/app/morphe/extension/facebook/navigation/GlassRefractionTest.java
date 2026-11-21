/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import static org.junit.Assert.*;

import org.junit.Test;

/** The Glass bend row's percent turned into the bend the rim and the bubble draw. */
public class GlassRefractionTest {
    @Test public void thePercentScalesTheBendAndStaysInTheRowsRange() {
        assertEquals(0f, GlassRefraction.scale(0), 0f);
        assertEquals(1f, GlassRefraction.scale(100), 0f);
        assertEquals(2f, GlassRefraction.scale(200), 0f);
        assertEquals("below the range", 0f, GlassRefraction.scale(-40), 0f);
        assertEquals("above the range", 2f, GlassRefraction.scale(900), 0f);
    }

    @Test public void theRimBandGrowsWithTheStrengthAndNeverPastItsCap() {
        assertEquals("no bend, no band", 0f, GlassRefraction.bandShare(0), 0f);
        assertEquals("standard is the band the rim has always had", GlassRefraction.RIM_BAND,
                GlassRefraction.bandShare(100), 0f);
        assertTrue(GlassRefraction.bandShare(150) > GlassRefraction.bandShare(100));
        assertTrue(GlassRefraction.bandShare(200) <= GlassRefraction.MAX_RIM_BAND);
    }

    /** The bend row's saved text: blank and not-a-number keep the standard, the range holds. */
    @Test public void theSavedTextPicksTheStandardOrAClampedPercent() {
        assertEquals(GlassRefraction.DEFAULT_BEND, GlassRefraction.bendPercent(null));
        assertEquals(GlassRefraction.DEFAULT_BEND, GlassRefraction.bendPercent(""));
        assertEquals(GlassRefraction.DEFAULT_BEND, GlassRefraction.bendPercent("abc"));
        assertEquals(50, GlassRefraction.bendPercent("50"));
        assertEquals(GlassRefraction.MIN_BEND, GlassRefraction.bendPercent("-5"));
        assertEquals(GlassRefraction.MAX_BEND, GlassRefraction.bendPercent("900"));
    }

    /** The rim mirrors the backdrop inside its band and leaves it alone past it. */
    @Test public void theRimSourceMirrorsInsideTheBandOnly() {
        float band = 10f;
        assertEquals(20f, GlassRefraction.rimSource(0f, band), 0f);
        assertEquals(10f, GlassRefraction.rimSource(10f, band), 0f);
        assertEquals(15f, GlassRefraction.rimSource(15f, band), 0f);
        assertEquals(30f, GlassRefraction.rimSource(30f, band), 0f);
    }

    /** Centre and rim together never pull the picture past {@link GlassRefraction#MAX_PULL}. */
    @Test public void theBendNeverTurnsThePictureInsideOut() {
        for (int percent = 0; percent <= 200; percent += 25) {
            float bend = GlassRefraction.scale(percent);
            float pull = GlassRefraction.centre(bend) + GlassRefraction.rimBend(bend);
            assertTrue(percent + "%: " + pull, pull <= GlassRefraction.MAX_PULL + 1e-6f);
            assertTrue(GlassRefraction.rimBend(bend) >= 0f);
        }
    }
}
