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
package se.hirt.diskspace.ui.render;

import javafx.scene.paint.Color;
import se.hirt.diskspace.model.DirectoryNode;
import se.hirt.diskspace.ui.theme.ColorScheme;
import se.hirt.diskspace.ui.theme.SectorPalette;

import java.util.*;
import java.util.function.ToIntFunction;

/**
 * Resolver for the Branch Families colouring mode.
 * <p>Unlike Classic, branch anchors are not always the immediate children of the requested scan root. If the scan root
 * is a "funnel" — one child owns almost all bytes and no other child is significant — this resolver walks down that
 * dominant-child chain and mutes the intermediate rings. Strong palette colours start at the first level with
 * meaningful branching, so scanning a container directory still highlights the useful branch families inside it.</p>
 * <ul>
 *   <li>{@code scanRoot} → {@link ColorScheme#surface()} for the hub.</li>
 *   <li>File sectors and reserved synthetic sectors keep the scheme-owned neutral greys.</li>
 *   <li>Funnel rings between {@code scanRoot} and the effective palette root render as subdued neutral grey.</li>
 *   <li>Children of the effective palette root get collision-avoided Branch Families anchor colours.</li>
 *   <li>Descendants derive from their branch anchor, with depth, sibling rank, and name jitter providing variation
 *       while preserving branch-family cohesion.</li>
 * </ul>
 */
public final class BranchFamiliesColorResolver implements NodeColorResolver {

	private static final double FUNNEL_DOMINANT_THRESHOLD = 0.90;
	private static final double FUNNEL_SIGNIFICANT_THRESHOLD = 0.05;

	private final ColorScheme scheme;
	/**
	 * Sibling rank lookup. Supplied by the host because it shares a cache with the sunburst layout, which clears its
	 * per-render cache at the start of every paint. Keeping rank lookup external avoids two competing caches for the
	 * same data.
	 */
	private final ToIntFunction<DirectoryNode> rankOf;

	private final Map<DirectoryNode, Color> colorCache = new IdentityHashMap<>();
	/**
	 * Maps each top-level family root to its allocated palette index. Allocation walks forward from
	 * {@code name.hashCode() % paletteSize} to the first unclaimed bucket — keeps two top-level siblings whose names
	 * hash identically from rendering in the same colour (e.g. "System" and "Applications" both hash to idx 11 on JDK
	 * 25).
	 */
	private final Map<DirectoryNode, Integer> topLevelPaletteIdx = new IdentityHashMap<>();
	/**
	 * Top-level folders whose descendants have already been "stabilised" (their cached colours dropped on the tick the
	 * folder finished). Membership prevents the stabilisation from re-running every tick.
	 */
	private final Set<DirectoryNode> finalizedTopLevels = Collections.newSetFromMap(
			new IdentityHashMap<DirectoryNode, Boolean>());

	private DirectoryNode scanRoot;
	private DirectoryNode hiddenNode;
	private DirectoryNode effectivePaletteRoot;

	public BranchFamiliesColorResolver(ColorScheme scheme, ToIntFunction<DirectoryNode> rankOf) {
		this.scheme = scheme;
		this.rankOf = rankOf;
	}

	/** Update the scan root reference. Clears all caches because family roots and ranks now resolve differently. */
	public void setScanRoot(DirectoryNode newScanRoot) {
		this.scanRoot = newScanRoot;
		colorCache.clear();
		topLevelPaletteIdx.clear();
		finalizedTopLevels.clear();
		effectivePaletteRoot = null;
	}

	/** Update the synthetic "Hidden" node reference so it can be excluded from per-tick stabilisation. */
	public void setHiddenNode(DirectoryNode newHiddenNode) {
		this.hiddenNode = newHiddenNode;
	}

	/**
	 * Called when a scan completes: drop colours and the top-level palette allocation so the final size-order picks the
	 * palette indices in size-descending order. A node briefly cached as rank-0 stays cached as rank-0 unless we
	 * invalidate; same for the top-level palette allocation.
	 */
	public void onScanComplete() {
		colorCache.clear();
		topLevelPaletteIdx.clear();
		effectivePaletteRoot = null;
	}

	/**
	 * Per-tick maintenance for live scans. Detect any top-level folder that just transitioned to {@code DONE} and drop
	 * its descendants' cached colours so the next render derives them against the now-final sort order. The top-level
	 * node itself stays cached because its colour is hash-based via {@link #allocateTopLevelIdx}, not rank-based — it
	 * doesn't shift during the scan.
	 */
	public void stabilizeFinalizedTopLevels() {
		if (scanRoot == null)
			return;
		DirectoryNode root = effectivePaletteRoot != null ? effectivePaletteRoot : scanRoot;
		for (DirectoryNode c : root.children()) {
			if (c == hiddenNode)
				continue;
			if (c.isDone() && finalizedTopLevels.add(c)) {
				clearDescendantColors(c);
			}
		}
	}

	@Override
	public Color colorFor(DirectoryNode node) {
		if (node == null || node == scanRoot)
			return scheme.surface();
		Color cached = colorCache.get(node);
		if (cached != null)
			return cached;

		if (effectivePaletteRoot == null && scanRoot != null) {
			effectivePaletteRoot = findEffectivePaletteRoot(scanRoot);
		}
		DirectoryNode paletteRoot = effectivePaletteRoot != null ? effectivePaletteRoot : scanRoot;

		Color computed;
		if (node.isFileSector()) {
			int d = depthFromScanRoot(node);
			computed = SectorPalette.forFileSector(scheme, node.name(), Math.max(0, d - 1));
		} else if ("Hidden".equals(node.name())) {
			// Hidden has its own reserved grey via SectorPalette.forName (scheme-owned).
			computed = SectorPalette.forName(scheme, "Hidden", 0);
		} else if (isFunnelNode(node)) {
			computed = SectorPalette.branchFamilyFunnelNode(scheme, Math.max(0, depthFromScanRoot(node) - 1));
		} else if (node.parent() == paletteRoot || node.parent() == null) {
			// Family root at the effective palette level — palette pick by name with collision avoidance.
			computed = SectorPalette.branchFamilyAtIndex(allocateTopLevelIdx(node), 0);
		} else {
			DirectoryNode familyRoot = familyRootFor(node, paletteRoot);
			int rootIdx = allocateTopLevelIdx(familyRoot);
			int d = depthFromScanRoot(node);
			computed = SectorPalette.branchFamilyForSubtree(rootIdx, node.name(), Math.max(0, d - 1), rankOf.applyAsInt(node));
		}
		colorCache.put(node, computed);
		return computed;
	}

	private boolean isFunnelNode(DirectoryNode node) {
		if (scanRoot == null || effectivePaletteRoot == null || effectivePaletteRoot == scanRoot)
			return false;
		for (DirectoryNode n = effectivePaletteRoot; n != null && n != scanRoot; n = n.parent()) {
			if (n == node)
				return true;
		}
		return false;
	}

	private DirectoryNode familyRootFor(DirectoryNode node, DirectoryNode paletteRoot) {
		DirectoryNode familyRoot = node;
		while (familyRoot.parent() != null && familyRoot.parent() != paletteRoot) {
			familyRoot = familyRoot.parent();
		}
		return familyRoot;
	}

	private DirectoryNode findEffectivePaletteRoot(DirectoryNode root) {
		DirectoryNode current = root;
		for (int depth = 0; depth < 100; depth++) {
			long total = current.totalBytes();
			if (total <= 0)
				return current;
			DirectoryNode dominantChild = null;
			int significantChildren = 0;
			for (DirectoryNode child : current.children()) {
				double fraction = child.totalBytes() / (double) total;
				if (fraction >= FUNNEL_SIGNIFICANT_THRESHOLD) {
					significantChildren++;
				}
				if (fraction >= FUNNEL_DOMINANT_THRESHOLD) {
					if (dominantChild != null)
						return current;
					dominantChild = child;
				}
			}
			if (dominantChild == null || significantChildren != 1)
				return current;
			current = dominantChild;
		}
		return current;
	}

	/**
	 * Returns the palette index this top-level family will use, allocating on first access. Starts from
	 * {@code name.hashCode() % paletteSize} and walks forward to the first index not already claimed by a
	 * previously-allocated sibling — so two top-level siblings whose names happen to hash to the same bucket can't
	 * render identical.
	 */
	private int allocateTopLevelIdx(DirectoryNode node) {
		Integer cached = topLevelPaletteIdx.get(node);
		if (cached != null)
			return cached;
		int n = SectorPalette.branchFamilyPaletteSize();
		Set<Integer> used = new HashSet<>(topLevelPaletteIdx.values());
		int idx = Math.floorMod(node.name().hashCode(), n);
		int tries = 0;
		while (used.contains(idx) && tries < n) {
			idx = (idx + 1) % n;
			tries++;
		}
		topLevelPaletteIdx.put(node, idx);
		return idx;
	}

	private int depthFromScanRoot(DirectoryNode node) {
		int d = 0;
		for (DirectoryNode n = node; n != null && n != scanRoot; n = n.parent())
			d++;
		return d;
	}

	private void clearDescendantColors(DirectoryNode root) {
		Deque<DirectoryNode> stack = new ArrayDeque<>();
		for (DirectoryNode c : root.children())
			stack.push(c);
		while (!stack.isEmpty()) {
			DirectoryNode n = stack.pop();
			colorCache.remove(n);
			for (DirectoryNode c : n.children())
				stack.push(c);
		}
	}
}
