/*******************************************************************************
 * Copyright 2011 See AUTHORS file.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package com.badlogic.gdx.tools.texturepacker;

import com.badlogic.gdx.utils.Array;

import static com.badlogic.gdx.tools.texturepacker.TexturePacker.*;

/** @author Nathan Sweet */
public class GridPacker implements MultiTexturePacker.Packer {
	private final Settings settings;

	public GridPacker (Settings settings) {
		this.settings = settings;
	}

	public Array<MultiTexturePacker.Page> pack (Array<MultiTexturePacker.Rect> inputRects) {
		return pack(null, inputRects);
	}

	public Array<MultiTexturePacker.Page> pack (ProgressListener progress, Array<MultiTexturePacker.Rect> inputRects) {
		if (!settings.silent) System.out.print("Packing");

		// Rects are packed with right and top padding, so the max size is increased to match. After packing the padding is
		// subtracted from the page size.
		int paddingX = settings.paddingX, paddingY = settings.paddingY;
		int adjustX = paddingX, adjustY = paddingY;
		if (settings.edgePadding) {
			if (settings.duplicatePadding) {
				adjustX -= paddingX;
				adjustY -= paddingY;
			} else {
				adjustX -= paddingX * 2;
				adjustY -= paddingY * 2;
			}
		}
		int maxWidth = settings.maxWidth + adjustX, maxHeight = settings.maxHeight + adjustY;

		int n = inputRects.size;
		int cellWidth = 0, cellHeight = 0;
		for (int i = 0; i < n; i++) {
			MultiTexturePacker.Rect rect = inputRects.get(i);
			cellWidth = Math.max(cellWidth, rect.width);
			cellHeight = Math.max(cellHeight, rect.height);
		}
		cellWidth += paddingX;
		cellHeight += paddingY;

		inputRects.reverse();

		Array<MultiTexturePacker.Page> pages = new Array();
		while (inputRects.size > 0) {
			if (progress != null && progress.update(n - inputRects.size + 1, n)) break;
			MultiTexturePacker.Page page = packPage(inputRects, cellWidth, cellHeight, maxWidth, maxHeight);
			page.width -= paddingX;
			page.height -= paddingY;
			pages.add(page);
		}
		return pages;
	}

	private MultiTexturePacker.Page packPage (Array<MultiTexturePacker.Rect> inputRects, int cellWidth, int cellHeight, int maxWidth, int maxHeight) {
		MultiTexturePacker.Page page = new MultiTexturePacker.Page();
		page.outputRects = new Array();

		int n = inputRects.size;
		int x = 0, y = 0;
		for (int i = n - 1; i >= 0; i--) {
			if (x + cellWidth > maxWidth) {
				y += cellHeight;
				if (y > maxHeight - cellHeight) break;
				x = 0;
			}
			MultiTexturePacker.Rect rect = inputRects.removeIndex(i);
			rect.x = x;
			rect.y = y;
			rect.width += settings.paddingX;
			rect.height += settings.paddingY;
			page.outputRects.add(rect);
			x += cellWidth;
			page.width = Math.max(page.width, x);
			page.height = Math.max(page.height, y + cellHeight);
		}

		// Flip so rows start at top.
		for (int i = page.outputRects.size - 1; i >= 0; i--) {
			MultiTexturePacker.Rect rect = page.outputRects.get(i);
			rect.y = page.height - rect.y - rect.height;
		}
		return page;
	}
}
