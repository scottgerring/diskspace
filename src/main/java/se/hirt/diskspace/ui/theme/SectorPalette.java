/*
 * Copyright (C) 2026 Marcus Hirt
 *
 * This software is free:
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. The name of the author may not be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESSED OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package se.hirt.diskspace.ui.theme;

import javafx.scene.paint.Color;

/**
 * Folder + file-sector colour helpers used by visualization coloring modes.
 * <p>The Classic 12-colour folder palette ({@link #atIndex}, {@link #forName}) is theme-independent — the same
 * saturated hues read well against both dark and light backgrounds, just slightly differently. The three file-sector
 * greys, on the other hand, are owned by the active {@link ColorScheme} ({@link ColorScheme#largeFileSector()},
 * {@link ColorScheme#smallerFilesSector()}, {@link ColorScheme#hiddenSector()}) so each theme picks values appropriate
 * to its background. {@link #forName} and {@link #forFileSector} therefore take a {@code ColorScheme} parameter;
 * adding a new theme is a matter of filling in those three fields, not editing this class.
 */
public final class SectorPalette {

	/** Twelve harmonious, saturated-but-not-screaming colors. Works on dark and light backgrounds. */
	private static final Color[] BASE = {Color.web("#5BC9C9"), Color.web("#4DA8E0"), Color.web("#6B6FE0"),
			Color.web("#9466E0"), Color.web("#C95FB7"), Color.web("#D85A8E"), Color.web("#E07260"),
			Color.web("#E0A04F"), Color.web("#A8C95F"), Color.web("#5FC982"), Color.web("#7AB8A0"),
			Color.web("#6B8FB5"),};

	/** Separate palette used by Branch Families. Initially mirrors Classic; hue tuning lives with that mode's colour change. */
	private static final Color[] BRANCH_FAMILY_BASE = {Color.web("#5BC9C9"), Color.web("#4DA8E0"), Color.web("#6B6FE0"),
			Color.web("#9466E0"), Color.web("#C95FB7"), Color.web("#D85A8E"), Color.web("#E07260"),
			Color.web("#E0A04F"), Color.web("#A8C95F"), Color.web("#5FC982"), Color.web("#7AB8A0"),
			Color.web("#6B8FB5"),};

	private SectorPalette() {
	}

	/**
	 * Number of distinct Classic palette colors. Callers can use this with {@link #atIndex(int, int)} to enumerate or to
	 * dedupe top-level color allocation.
	 */
	public static int paletteSize() {
		return BASE.length;
	}

	/**
	 * Returns the Classic palette color at {@code i}, with the same depth-darkening behavior as {@link #forName}.
	 * {@code i} is taken modulo {@link #paletteSize()}. Used by collision-avoidance code that needs a specific index,
	 * not a hashed one.
	 */
	public static Color atIndex(int i, int depth) {
		Color base = BASE[Math.floorMod(i, BASE.length)];
		return darkenForDepth(base, depth);
	}

	/** Number of distinct Branch Families anchor colours. */
	public static int branchFamilyPaletteSize() {
		return BRANCH_FAMILY_BASE.length;
	}

	/** Returns a Branch Families anchor colour at {@code i}. */
	public static Color branchFamilyAtIndex(int i, int depth) {
		Color base = BRANCH_FAMILY_BASE[Math.floorMod(i, BRANCH_FAMILY_BASE.length)];
		return darkenForDepth(base, depth);
	}

	/**
	 * Folder colour by hashed name. Two reserved names route to the scheme-owned greys: {@code "Hidden"} →
	 * {@link ColorScheme#hiddenSector()}, {@code "Smaller files"} → {@link ColorScheme#smallerFilesSector()}.
	 * Everything else picks from {@link #BASE} by {@code name.hashCode()}.
	 */
	public static Color forName(ColorScheme scheme, String name, int depth) {
		if ("Hidden".equals(name)) {
			return darkenForDepth(scheme.hiddenSector(), depth);
		}
		if ("Smaller files".equals(name)) {
			return darkenForDepth(scheme.smallerFilesSector(), depth);
		}
		int hash = name == null ? 0 : name.hashCode();
		Color base = BASE[Math.floorMod(hash, BASE.length)];
		return darkenForDepth(base, depth);
	}

	/**
	 * Derives a Branch Families descendant colour from an already-allocated family palette index. This keeps every folder
	 * below a family root recognisably in that root's colour family while still varying siblings enough to distinguish
	 * neighbouring sectors.
	 */
	public static Color branchFamilyForSubtree(int rootIndex, String childName, int depth, int siblingRank) {
		Color base = BRANCH_FAMILY_BASE[Math.floorMod(rootIndex, BRANCH_FAMILY_BASE.length)];
		int hash = childName == null ? 0 : childName.hashCode();

		// Keep the branch family hue coherent, but make depth read clearly. This is intentionally more value-driven than
		// hue-driven: drilled-in views should stay recognisably one family while rings and sibling lanes remain legible.
		double brightFactor = 1.10 - depth * 0.11;
		double saturationFactor = 1.0 - depth * 0.045;

		if (siblingRank >= 0) {
			// Alternate sibling lanes light/dark rather than monotonically darkening them. Adjacent sectors become easier to
			// distinguish, while the clamp keeps tiny many-sibling directories from turning into zebra stripes.
			double laneMagnitude = Math.min(0.18, 0.08 + (siblingRank % 4) * 0.035);
			double laneSign = (siblingRank % 2 == 0) ? 1.0 : -1.0;
			brightFactor += laneSign * laneMagnitude;
			// Larger ranks recede slightly in saturation, which separates small outer spokes without changing family hue.
			saturationFactor -= Math.min(0.18, siblingRank * 0.018);
		}

		double hueJitter = Math.floorMod(hash, 13) - 6.0; // -6° .. +6°: enough to avoid flatness, not enough to rainbow.
		brightFactor = Math.max(0.48, Math.min(1.18, brightFactor));
		saturationFactor = Math.max(0.62, Math.min(1.0, saturationFactor));
		return base.deriveColor(hueJitter, saturationFactor, brightFactor, 1.0);
	}

	/**
	 * Muted neutral for Branch Families funnel rings: visible enough to explain the path from the requested scan root, but
	 * deliberately quieter than the palette families that start below it.
	 */
	public static Color branchFamilyFunnelNode(ColorScheme scheme, int depth) {
		Color base = scheme.isDark() ? Color.web("#4A4E56") : Color.web("#D2D5DA");
		return darkenForDepth(base.deriveColor(0, 0.80, 1.0, 1.0), depth);
	}

	/**
	 * Color for a file-sector node: {@code "Smaller files"} aggregates pick up the
	 * {@link ColorScheme#smallerFilesSector()} grey via {@link #forName}; an individual large file gets
	 * {@link ColorScheme#largeFileSector()} so files don't compete visually with folders.
	 */
	public static Color forFileSector(ColorScheme scheme, String name, int depth) {
		if ("Smaller files".equals(name)) {
			return forName(scheme, name, depth);
		}
		return darkenForDepth(scheme.largeFileSector(), depth);
	}

	/**
	 * Slightly darken with depth so deeper rings recede visually. The {@code 0.55} floor stops a deep tree from going
	 * fully black. Applied uniformly to folder colours and to the three scheme-owned sector greys — for sector greys on
	 * a light theme this means the colour darkens away from the background (more contrast, not less), which is the
	 * direction we want for "deeper ring sits 'lower' than its parent."
	 */
	private static Color darkenForDepth(Color base, int depth) {
		double factor = Math.max(0.55, 1.0 - depth * 0.08);
		return base.deriveColor(0, 1.0, factor, 1.0);
	}
}
