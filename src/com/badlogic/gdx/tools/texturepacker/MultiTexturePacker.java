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

import java.awt.*;
import java.awt.image.RasterFormatException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.Buffer;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap.Format;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.graphics.Texture.TextureWrap;
import com.badlogic.gdx.graphics.g2d.TextureAtlas.TextureAtlasData;
import com.badlogic.gdx.graphics.g2d.TextureAtlas.TextureAtlasData.Region;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.tools.texturepacker.TexturePacker.Settings;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.utils.GdxRuntimeException;
import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.ObjectMap;

import java.awt.image.BufferedImage;

import static com.badlogic.gdx.tools.texturepacker.TexturePacker.*;

 /** @author Nathan Sweet */
public class MultiTexturePacker {
	private final Settings settings;
	private final Packer packer;
	private final ImageProcessor imageProcessor;
	private final Array<InputImage> inputImages = new Array();
	private File rootDir;
	private ProgressListener progress;
	private String[] originalInputs;
	private String[] additionalOutputs;
	private String[] additionalFileSuffixes;

	/** @param rootDir Used to strip the root directory prefix from image file names, can be null. */
	public MultiTexturePacker (File rootDir, Settings settings) {
		this.rootDir = rootDir;
		this.settings = settings;

		if (settings.pot) {
			if (settings.maxWidth != MathUtils.nextPowerOfTwo(settings.maxWidth))
				throw new RuntimeException("If pot is true, maxWidth must be a power of two: " + settings.maxWidth);
			if (settings.maxHeight != MathUtils.nextPowerOfTwo(settings.maxHeight))
				throw new RuntimeException("If pot is true, maxHeight must be a power of two: " + settings.maxHeight);
		}

		if (settings.multipleOfFour) {
			if (settings.maxWidth % 4 != 0)
				throw new RuntimeException("If mod4 is true, maxWidth must be evenly divisible by 4: " + settings.maxWidth);
			if (settings.maxHeight % 4 != 0)
				throw new RuntimeException("If mod4 is true, maxHeight must be evenly divisible by 4: " + settings.maxHeight);
		}

		if (settings.grid)
			packer = new GridPacker(settings);
		else
			packer = new MaxRectsPacker(settings);
		imageProcessor = new ImageProcessor(rootDir, settings);
	}

	public MultiTexturePacker (Settings settings) {
		this(null, settings);
	}

	public void addImage (File file) {
		InputImage inputImage = new InputImage();
		inputImage.file = file;
		inputImages.add(inputImage);
	}

	public void addImage (BufferedImage image, String name) {
		InputImage inputImage = new InputImage();
		inputImage.image = image;
		inputImage.name = name;
		inputImages.add(inputImage);
	}

	public void pack (File outputDir, String packFileName) {
		if (packFileName.endsWith(settings.atlasExtension))
			packFileName = packFileName.substring(0, packFileName.length() - settings.atlasExtension.length());
		outputDir.mkdirs();

		if (progress == null) {
			progress = new ProgressListener() {
				public void progress (float progress) {
				}
			};
		}
		progress.reset();

		progress.start(1);
		int n = settings.scale.length;
		for (int i = 0; i < n; i++) {
			progress.start(1f / n);

			progress.start(0.35f);
			imageProcessor.setScale(settings.scale[i]);

			if (settings.scaleResampling != null && settings.scaleResampling.length > i && settings.scaleResampling[i] != null)
				imageProcessor.setResampling(settings.scaleResampling[i]);

			for (int ii = 0, nn = inputImages.size; ii < nn; ii++) {
				InputImage inputImage = inputImages.get(ii);
                if (inputImage.file != null) {
                	if (additionalFileSuffixes.length > 0) {
						String name = inputImage.file.getAbsolutePath();
						String[] split = name.split("\\.");
						StringBuilder emissiveName = new StringBuilder(split[0] + "_emissive");
						for (int j = 1; j < split.length; j++) {
							emissiveName.append(".").append(split[j]);
						}
						File file = new File(emissiveName.toString());
						try {
							BufferedImage read = ImageIO.read(file);
							imageProcessor.addImage(inputImage.file, read);
						} catch (Exception e) {
							imageProcessor.addImage(inputImage.file, null);
						}
					} else {
						imageProcessor.addImage(inputImage.file, null);
					}
                }
				else
					imageProcessor.addImage(inputImage.image, inputImage.name, null);
				if (progress.update(ii + 1, nn)) return;
			}
			progress.end();

			progress.start(0.19f);
			Array<Page> pages = packer.pack(progress, imageProcessor.getImages());
			progress.end();

			progress.start(0.45f);
			String scaledPackFileName = settings.getScaledPackFileName(packFileName, i);
			writeImages(outputDir, scaledPackFileName, pages);
			progress.end();

			progress.start(0.01f);
			try {
				writePackFile(outputDir, scaledPackFileName, pages);
				for (String additionalOutput : additionalOutputs) {
					writePackFile(new File(additionalOutput), scaledPackFileName, pages);
				}
			} catch (IOException ex) {
				throw new RuntimeException("Error writing pack file.", ex);
			}
			imageProcessor.clear();
			progress.end();

			progress.end();

			if (progress.update(i + 1, n)) return;
		}
		progress.end();
	}

	private void writeImages (File outputDir, String scaledPackFileName, Array<Page> pages) {
		File packFileNoExt = new File(outputDir, scaledPackFileName);
		File packDir = packFileNoExt.getParentFile();
		String imageName = packFileNoExt.getName();

		int fileIndex = 0;
		for (int p = 0, pn = pages.size; p < pn; p++) {
			Page page = pages.get(p);

			int width = page.width, height = page.height;
			int edgePadX = 0, edgePadY = 0;
			if (settings.edgePadding) {
				edgePadX = settings.paddingX;
				edgePadY = settings.paddingY;
				if (settings.duplicatePadding) {
					edgePadX /= 2;
					edgePadY /= 2;
				}
				page.x = edgePadX;
				page.y = edgePadY;
				width += edgePadX * 2;
				height += edgePadY * 2;
			}
			if (settings.pot) {
				width = MathUtils.nextPowerOfTwo(width);
				height = MathUtils.nextPowerOfTwo(height);
			}
			if (settings.multipleOfFour) {
				width = width % 4 == 0 ? width : width + 4 - (width % 4);
				height = height % 4 == 0 ? height : height + 4 - (height % 4);
			}
			width = Math.max(settings.minWidth, width);
			height = Math.max(settings.minHeight, height);
			page.imageWidth = width;
			page.imageHeight = height;

			File outputFile;
			while (true) {
				outputFile = new File(packDir, imageName + (fileIndex++ == 0 ? "" : fileIndex) + "." + settings.outputFormat);
				if (!outputFile.exists()) break;
			}
			new FileHandle(outputFile).parent().mkdirs();
			page.imageName = outputFile.getName();

			BufferedImage canvas = new BufferedImage(width, height, getBufferedImageType(settings.format));
			Graphics2D g = (Graphics2D)canvas.getGraphics();

			ObjectMap<String, BufferedImage> additionalImages = new ObjectMap<>();
			ObjectMap<String, Graphics2D> additionalGraphics = new ObjectMap<>();

			for (String additionalOutput : additionalOutputs) {
				BufferedImage value = new BufferedImage(width, height, getBufferedImageType(settings.format));
				additionalImages.put(additionalOutput, value);
				additionalGraphics.put(additionalOutput, (Graphics2D)value.getGraphics());
			}

			if (!settings.silent) System.out.println("Writing " + canvas.getWidth() + "x" + canvas.getHeight() + ": " + outputFile);

			progress.start(1 / (float)pn);
			for (int r = 0, rn = page.outputRects.size; r < rn; r++) {
				if (packOutputRectForGraphics(page, canvas, g, r, rn, additionalImages, additionalGraphics))
					return;
			}
			progress.end();

			if (settings.bleed && !settings.premultiplyAlpha
				&& !(settings.outputFormat.equalsIgnoreCase("jpg") || settings.outputFormat.equalsIgnoreCase("jpeg"))) {
				canvas = new ColorBleedEffect().processImage(canvas, settings.bleedIterations);
				g = (Graphics2D)canvas.getGraphics();
			}

			if (settings.debug) {
				g.setColor(Color.magenta);
				g.drawRect(0, 0, width - 1, height - 1);
			}

			writeCanvas(outputFile, canvas);

			for (String additionalOutput : additionalOutputs) {
				while (true) {
					outputFile = new File(additionalOutput, imageName + (fileIndex - 1 == 0 ? "" : fileIndex) + "." + settings.outputFormat);
					if (!outputFile.exists()) break;
				}
				new FileHandle(outputFile).parent().mkdirs();

				writeCanvas(outputFile, additionalImages.get(additionalOutput));
			}

			if (progress.update(p + 1, pn)) return;
		}
	}

	private void writeCanvas (File outputFile, BufferedImage canvas) {
		ImageOutputStream ios = null;
		try {
			if (settings.outputFormat.equalsIgnoreCase("jpg") || settings.outputFormat.equalsIgnoreCase("jpeg")) {
				BufferedImage newImage = new BufferedImage(canvas.getWidth(), canvas.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
				newImage.getGraphics().drawImage(canvas, 0, 0, null);
				canvas = newImage;

				Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
				ImageWriter writer = writers.next();
				ImageWriteParam param = writer.getDefaultWriteParam();
				param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
				param.setCompressionQuality(settings.jpegQuality);
				ios = ImageIO.createImageOutputStream(outputFile);
				writer.setOutput(ios);
				writer.write(null, new IIOImage(canvas, null, null), param);
			} else {
				if (settings.premultiplyAlpha) canvas.getColorModel().coerceData(canvas.getRaster(), true);
				ImageIO.write(canvas, "png", outputFile);
			}
		} catch (IOException ex) {
			throw new RuntimeException("Error writing file: " + outputFile, ex);
		} finally {
			if (ios != null) {
				try {
					ios.close();
				} catch (Exception ignored) {
				}
			}
		}
	}

	private boolean packOutputRectForGraphics (Page page, BufferedImage canvas, Graphics2D g, int r, int rn,
		ObjectMap<String, BufferedImage> additionalImages, ObjectMap<String, Graphics2D> additionalGraphics) {
		Rect rect = page.outputRects.get(r);

		BufferedImage image = rect.getImage(imageProcessor);

		rect.image = image;

		rect.mergeWithAdditionals(imageProcessor, originalInputs, additionalFileSuffixes);

		putImageIntoGraphics(page, canvas, g, rect, rect.image);
		for (int i = 0; i < additionalOutputs.length; i++) {

			BufferedImage bufferImage = additionalImages.get(this.additionalOutputs[i]);
			Graphics2D graphics = additionalGraphics.get(this.additionalOutputs[i]);

			BufferedImage twinImage = rect.twinImages[i];

			putImageIntoGraphics(page, bufferImage, graphics, rect, twinImage);
		}

		if (progress.update(r + 1, rn))
			return true;
		return false;
	}

	private void putImageIntoGraphics (Page page, BufferedImage canvas, Graphics2D g, Rect rect, BufferedImage image) {

		int iw = image.getWidth();
		int ih = image.getHeight();
		int rectX = page.x + rect.x, rectY = page.y + page.height - rect.y - (rect.height - settings.paddingY);
		if (settings.duplicatePadding) {
			int amountX = settings.paddingX / 2;
			int amountY = settings.paddingY / 2;
			if (rect.rotated) {
				// Copy corner pixels to fill corners of the padding.
				for (int i = 1; i <= amountX; i++) {
					for (int j = 1; j <= amountY; j++) {
						plot(canvas, rectX - j, rectY + iw - 1 + i, image.getRGB(0, 0));
						plot(canvas, rectX + ih - 1 + j, rectY + iw - 1 + i, image.getRGB(0, ih - 1));
						plot(canvas, rectX - j, rectY - i, image.getRGB(iw - 1, 0));
						plot(canvas, rectX + ih - 1 + j, rectY - i, image.getRGB(iw - 1, ih - 1));
					}
				}
				// Copy edge pixels into padding.
				for (int i = 1; i <= amountY; i++) {
					for (int j = 0; j < iw; j++) {
						plot(canvas, rectX - i, rectY + iw - 1 - j, image.getRGB(j, 0));
						plot(canvas, rectX + ih - 1 + i, rectY + iw - 1 - j, image.getRGB(j, ih - 1));
					}
				}
				for (int i = 1; i <= amountX; i++) {
					for (int j = 0; j < ih; j++) {
						plot(canvas, rectX + j, rectY - i, image.getRGB(iw - 1, j));
						plot(canvas, rectX + j, rectY + iw - 1 + i, image.getRGB(0, j));
					}
				}
			} else {
				// Copy corner pixels to fill corners of the padding.
				for (int i = 1; i <= amountX; i++) {
					for (int j = 1; j <= amountY; j++) {
						plot(canvas, rectX - i, rectY - j, image.getRGB(0, 0));
						plot(canvas, rectX - i, rectY + ih - 1 + j, image.getRGB(0, ih - 1));
						plot(canvas, rectX + iw - 1 + i, rectY - j, image.getRGB(iw - 1, 0));
						plot(canvas, rectX + iw - 1 + i, rectY + ih - 1 + j, image.getRGB(iw - 1, ih - 1));
					}
				}
				// Copy edge pixels into padding.
				for (int i = 1; i <= amountY; i++) {
					copy(image, 0, 0, iw, 1, canvas, rectX, rectY - i, rect.rotated);
					copy(image, 0, ih - 1, iw, 1, canvas, rectX, rectY + ih - 1 + i, rect.rotated);
				}
				for (int i = 1; i <= amountX; i++) {
					copy(image, 0, 0, 1, ih, canvas, rectX - i, rectY, rect.rotated);
					copy(image, iw - 1, 0, 1, ih, canvas, rectX + iw - 1 + i, rectY, rect.rotated);
				}
			}
		}
		copy(image, 0, 0, iw, ih, canvas, rectX, rectY, rect.rotated);
		if (settings.debug) {
			g.setColor(Color.magenta);
			g.drawRect(rectX, rectY, rect.width - settings.paddingX - 1, rect.height - settings.paddingY - 1);
		}
	}

	static private void plot (BufferedImage dst, int x, int y, int argb) {
		if (0 <= x && x < dst.getWidth() && 0 <= y && y < dst.getHeight()) dst.setRGB(x, y, argb);
	}

	static private void copy (BufferedImage src, int x, int y, int w, int h, BufferedImage dst, int dx, int dy, boolean rotated) {
		if (rotated) {
			for (int i = 0; i < w; i++)
				for (int j = 0; j < h; j++)
					plot(dst, dx + j, dy + w - i - 1, src.getRGB(x + i, y + j));
		} else {
			for (int i = 0; i < w; i++)
				for (int j = 0; j < h; j++)
					plot(dst, dx + i, dy + j, src.getRGB(x + i, y + j));
		}
	}

	private void writePackFile (File outputDir, String scaledPackFileName, Array<Page> pages) throws IOException {
		File packFile = new File(outputDir, scaledPackFileName + settings.atlasExtension);
		File packDir = packFile.getParentFile();
		packDir.mkdirs();

		if (packFile.exists()) {
			// Make sure there aren't duplicate names.
			TextureAtlasData textureAtlasData = new TextureAtlasData(new FileHandle(packFile), new FileHandle(packFile), false);
			for (Page page : pages) {
				for (Rect rect : page.outputRects) {
					String rectName = Rect.getAtlasName(rect.name, settings.flattenPaths);
					for (Region region : textureAtlasData.getRegions()) {
						if (region.name.equals(rectName)) {
							throw new GdxRuntimeException(
								"A region with the name \"" + rectName + "\" has already been packed: " + rect.name);
						}
					}
				}
			}
		}

		Writer writer = new OutputStreamWriter(new FileOutputStream(packFile, true), "UTF-8");
		for (Page page : pages) {
			writer.write("\n" + page.imageName + "\n");
			writer.write("size: " + page.imageWidth + "," + page.imageHeight + "\n");
			writer.write("format: " + settings.format + "\n");
			writer.write("filter: " + settings.filterMin + "," + settings.filterMag + "\n");
			writer.write("repeat: " + getRepeatValue() + "\n");

			page.outputRects.sort();
			for (Rect rect : page.outputRects) {
				writeRect(writer, page, rect, rect.name);
				Array<Alias> aliases = new Array(rect.aliases.toArray());
				aliases.sort();
				for (Alias alias : aliases) {
					Rect aliasRect = new Rect();
					aliasRect.set(rect);
					alias.apply(aliasRect);
					writeRect(writer, page, aliasRect, alias.name);
				}
			}
		}
		writer.close();
	}

	private void writeRect (Writer writer, Page page, Rect rect, String name) throws IOException {
		writer.write(Rect.getAtlasName(name, settings.flattenPaths) + "\n");
		writer.write("  rotate: " + rect.rotated + "\n");
		writer
			.write("  xy: " + (page.x + rect.x) + ", " + (page.y + page.height - rect.y - (rect.height - settings.paddingY)) + "\n");

		writer.write("  size: " + rect.regionWidth + ", " + rect.regionHeight + "\n");
		if (rect.splits != null) {
			writer.write("  split: " //
				+ rect.splits[0] + ", " + rect.splits[1] + ", " + rect.splits[2] + ", " + rect.splits[3] + "\n");
		}
		if (rect.pads != null) {
			if (rect.splits == null) writer.write("  split: 0, 0, 0, 0\n");
			writer.write("  pad: " + rect.pads[0] + ", " + rect.pads[1] + ", " + rect.pads[2] + ", " + rect.pads[3] + "\n");
		}
		writer.write("  orig: " + rect.originalWidth + ", " + rect.originalHeight + "\n");
		writer.write("  offset: " + rect.offsetX + ", " + (rect.originalHeight - rect.regionHeight - rect.offsetY) + "\n");
		writer.write("  index: " + rect.index + "\n");
	}

	private String getRepeatValue () {
		if (settings.wrapX == TextureWrap.Repeat && settings.wrapY == TextureWrap.Repeat) return "xy";
		if (settings.wrapX == TextureWrap.Repeat && settings.wrapY == TextureWrap.ClampToEdge) return "x";
		if (settings.wrapX == TextureWrap.ClampToEdge && settings.wrapY == TextureWrap.Repeat) return "y";
		return "none";
	}

	private int getBufferedImageType (Format format) {
		switch (settings.format) {
		case RGBA8888:
		case RGBA4444:
			return BufferedImage.TYPE_INT_ARGB;
		case RGB565:
		case RGB888:
			return BufferedImage.TYPE_INT_RGB;
		case Alpha:
			return BufferedImage.TYPE_BYTE_GRAY;
		default:
			throw new RuntimeException("Unsupported format: " + settings.format);
		}
	}

	public void setProgressListener (ProgressListener progressListener) {
		this.progress = progressListener;
	}

	/** @author Nathan Sweet */
	static public class Page {
		public String imageName;
		public Array<Rect> outputRects, remainingRects;
		public float occupancy;
		public int x, y, width, height, imageWidth, imageHeight;
	}

	/** @author Regnarock
	 * @author Nathan Sweet */
	static public class Alias implements Comparable<Alias> {
		public String name;
		public int index;
		public int[] splits;
		public int[] pads;
		public int offsetX, offsetY, originalWidth, originalHeight;

		public Alias (Rect rect) {
			name = rect.name;
			index = rect.index;
			splits = rect.splits;
			pads = rect.pads;
			offsetX = rect.offsetX;
			offsetY = rect.offsetY;
			originalWidth = rect.originalWidth;
			originalHeight = rect.originalHeight;
		}

		public void apply (Rect rect) {
			rect.name = name;
			rect.index = index;
			rect.splits = splits;
			rect.pads = pads;
			rect.offsetX = offsetX;
			rect.offsetY = offsetY;
			rect.originalWidth = originalWidth;
			rect.originalHeight = originalHeight;
		}

		public int compareTo (Alias o) {
			return name.compareTo(o.name);
		}
	}

	/** @author Nathan Sweet */
	static public class Rect implements Comparable<Rect> {
		public String name;
		public int offsetX, offsetY, regionWidth, regionHeight, originalWidth, originalHeight;
		public int x, y;
		public int width, height; // Portion of page taken by this region, including padding.
		public int index;
		public boolean rotated;
		public Set<Alias> aliases = new HashSet<Alias>();
		public int[] splits;
		public int[] pads;
		public boolean canRotate = true;

		private boolean isPatch;
		private BufferedImage image;
		private BufferedImage[] twinImages = new BufferedImage[10];
		private File file;
		int score1, score2;

		Rect (BufferedImage source, int left, int top, int newWidth, int newHeight, boolean isPatch) {
//			image = new BufferedImage(source.getColorModel(),
//				source.getRaster().createWritableChild(left, top, newWidth, newHeight, 0, 0, null),
//				source.getColorModel().isAlphaPremultiplied(), null);
			this.image = source;
			offsetX = left;
			offsetY = top;
			regionWidth = newWidth;
			regionHeight = newHeight;
			originalWidth = source.getWidth();
			originalHeight = source.getHeight();
			width = newWidth;
			height = newHeight;
			this.isPatch = isPatch;
		}

		Rect (BufferedImage source, int left, int top, int newWidth, int newHeight, boolean isPatch, BufferedImage twinImage) {
			this(source, left, top, newWidth, newHeight, isPatch);
//			image = new BufferedImage(source.getColorModel(),
//					source.getRaster().createWritableChild(left, top, newWidth, newHeight, 0, 0, null),
//					source.getColorModel().isAlphaPremultiplied(), null);
			offsetX = left;
			offsetY = top;
			regionWidth = newWidth;
			regionHeight = newHeight;
			originalWidth = source.getWidth();
			originalHeight = source.getHeight();
			width = newWidth;
			height = newHeight;
			this.isPatch = isPatch;
		}

		/** Clears the image for this rect, which will be loaded from the specified file by {@link #getImage(ImageProcessor)}. */
		public void unloadImage (File file) {
			this.file = file;
			image = null;
		}

		public BufferedImage getImage (ImageProcessor imageProcessor) {
			if (image != null) return image;

			BufferedImage image;
			try {
				image = ImageIO.read(file);
			} catch (IOException ex) {
				throw new RuntimeException("Error reading image: " + file, ex);
			}
			if (image == null) throw new RuntimeException("Unable to read image: " + file);
			String name = this.name;
			if (isPatch) name += ".9";

			return imageProcessor.processImage(image, name, null).getImage(null);
		}


		public void mergeWithAdditionals (ImageProcessor imageProcessor, String[] originalInputs, String[] additionalFileSuffixes) {
			for (int i = 0; i < originalInputs.length; i++) {
				String originalInput = originalInputs[i];
				String additionalFileSuffix = additionalFileSuffixes[i];
				twinImages[i] = getImage(imageProcessor, originalInput, additionalFileSuffix);

				if (isPatch) continue;

				Rect out = imageProcessor.processImage(image, name, twinImages[i]);

				this.offsetX = out.offsetX;
				this.offsetY = out.offsetY;
				this.width = out.width;
				this.height = out.height;

				this.regionWidth = out.regionWidth;
				this.regionHeight = out.regionHeight;

				this.originalWidth = out.originalWidth;
				this.originalHeight = out.originalHeight;

			}


			this.image = new BufferedImage(image.getColorModel(),
					image.getRaster().createWritableChild(this.offsetX, this.offsetY, this.regionWidth, this.regionHeight, 0, 0, null),
					image.getColorModel().isAlphaPremultiplied(), null);

			for (int i = 0; i < twinImages.length; i++) {
				BufferedImage twinImage = twinImages[i];
				if (twinImage != null) {
					twinImages[i] = new BufferedImage(twinImage.getColorModel(),
							twinImage.getRaster().createWritableChild(this.offsetX, this.offsetY, this.regionWidth, this.regionHeight, 0, 0, null),
							twinImage.getColorModel().isAlphaPremultiplied(), null);
				}
			}
		}

		public BufferedImage getImage (ImageProcessor imageProcessor, String originalDirToLookIn, String fileSuffix) {
			BufferedImage image = null;
			File newFile;

			String indexString = "";

			if (index != -1) {
				indexString = "_" + index;
			}

			try {
				if (isPatch) {
					newFile = new File(originalDirToLookIn, name + indexString + fileSuffix + ".9" + ".png");
				} else {
					newFile = new File(originalDirToLookIn, name + indexString + fileSuffix + ".png");
				}

				image = ImageIO.read(newFile);

				if (isPatch) {
					image = new BufferedImage(image.getColorModel(),
							image.getRaster().createWritableChild(1, 1, image.getWidth()-2, image.getHeight()-2, 0, 0, null),
							image.getColorModel().isAlphaPremultiplied(), null);
				}

				return image;
			} catch (IOException ex) {

				if (isPatch) {
					newFile = new File(originalDirToLookIn, name + indexString + ".9" + ".png");
				} else {
					newFile = new File(originalDirToLookIn, name + indexString + ".png");
				}

				try {
					image = ImageIO.read(newFile);

					int startX = 0;
					int startY = 0;
					int endX = image.getWidth();
					int endY = image.getHeight();

					int patchX = 0;
					int patchY = 0;

					if (isPatch) {
						patchX = 1;
						patchY = 1;
					}

					BufferedImage emissive = new BufferedImage(image.getWidth() - (patchX*2), image.getHeight() - (patchY*2), BufferedImage.TYPE_4BYTE_ABGR);



					startX += patchX;
					startY += patchY;
					endX -= patchX;
					endY -= patchY;

					for (int x = startX; x < endX; x++) {
						for (int y = startY; y < endY; y++) {
							Color pixelColor = new Color(image.getRGB(x, y), true);
							int r = 0;
							int g = 0;
							int b = 0;
							int a = pixelColor.getAlpha();
							int rgba = (a << 24) | (r << 16) | (g << 8) | b;
							try {
								emissive.setRGB(x - patchX, y - patchY, rgba);
							} catch (ArrayIndexOutOfBoundsException e) {
								System.out.println();
							}
						}
					}

					image = emissive;

					return image;

				} catch (IOException e) {
					throw new RuntimeException("No image found in original input directory: " + newFile.getAbsolutePath());
				}
			}
		}


		Rect () {
		}

		Rect (Rect rect) {
			x = rect.x;
			y = rect.y;
			width = rect.width;
			height = rect.height;
		}

		void set (Rect rect) {
			name = rect.name;
			image = rect.image;
			offsetX = rect.offsetX;
			offsetY = rect.offsetY;
			regionWidth = rect.regionWidth;
			regionHeight = rect.regionHeight;
			originalWidth = rect.originalWidth;
			originalHeight = rect.originalHeight;
			x = rect.x;
			y = rect.y;
			width = rect.width;
			height = rect.height;
			index = rect.index;
			rotated = rect.rotated;
			aliases = rect.aliases;
			splits = rect.splits;
			pads = rect.pads;
			canRotate = rect.canRotate;
			score1 = rect.score1;
			score2 = rect.score2;
			file = rect.file;
			isPatch = rect.isPatch;
		}

		public int compareTo (Rect o) {
			return name.compareTo(o.name);
		}

		@Override
		public boolean equals (Object obj) {
			if (this == obj) return true;
			if (obj == null) return false;
			if (getClass() != obj.getClass()) return false;
			Rect other = (Rect)obj;
			if (name == null) {
				if (other.name != null) return false;
			} else if (!name.equals(other.name)) return false;
			return true;
		}

		@Override
		public String toString () {
			return name + (index != -1 ? "_" + index : "") + "[" + x + "," + y + " " + width + "x" + height + "]";
		}

		static public String getAtlasName (String name, boolean flattenPaths) {
			return flattenPaths ? new FileHandle(name).name() : name;
		}


	}

	/** @param input Directory containing individual images to be packed.
	 * @param output Directory where the pack file and page images will be written.
	 * @param packFileName The name of the pack file. Also used to name the page images.
	 * @param progress May be null. */
	static public void process (Settings settings, String input, String output, String packFileName, String[] additionalFileSuffixes, String[] additionalOutputs,
		final ProgressListener progress) {
		try {
			TexturePackerFileProcessor processor = new TexturePackerFileProcessor(settings, packFileName) {
				protected MultiTexturePacker newTexturePacker (File root, Settings settings) {
					MultiTexturePacker packer = super.newTexturePacker(root, settings);
					packer.setOriginals(input);
					packer.setAdditionals(additionalOutputs, additionalFileSuffixes);
					packer.setProgressListener(progress);
					return packer;
				}
			};
			// Sort input files by name to avoid platform-dependent atlas output changes.
			processor.setComparator(new Comparator<File>() {
				public int compare (File file1, File file2) {
					return file1.getName().compareTo(file2.getName());
				}
			});
			File[] additionalOuts = new File[additionalOutputs.length];
			for (int i = 0; i < additionalOutputs.length; i++) {
				String additionalOutput = additionalOutputs[i];
				additionalOuts[i] = new File(additionalOutput);
			}
			processor.process(new File(input), new File(output), additionalOuts);
		} catch (Exception ex) {
			throw new RuntimeException("Error packing images.", ex);
		}
	}

	private void setOriginals (String input) {
		this.originalInputs = new String[] {input};
	}

	private void setAdditionals (String[] additionalOutputs, String[] additionalFileSuffixes) {
		int length = additionalOutputs.length;
		if (additionalFileSuffixes.length != length) {
			throw new GdxRuntimeException("Additionals not valid");
		}

		this.additionalOutputs = additionalOutputs;
		this.additionalFileSuffixes = additionalFileSuffixes;
	}

	/** @return true if the output file does not yet exist or its last modification date is before the last modification date of
	 *         the input file */
	static public boolean isModified (String input, String output, String packFileName, Settings settings) {
		String packFullFileName = output;

		if (!packFullFileName.endsWith("/")) {
			packFullFileName += "/";
		}

		// Check against the only file we know for sure will exist and will be changed if any asset changes:
		// the atlas file
		packFullFileName += packFileName;
		packFullFileName += settings.atlasExtension;
		File outputFile = new File(packFullFileName);

		if (!outputFile.exists()) {
			return true;
		}

		File inputFile = new File(input);
		if (!inputFile.exists()) {
			throw new IllegalArgumentException("Input file does not exist: " + inputFile.getAbsolutePath());
		}

		return isModified(inputFile, outputFile.lastModified());
	}

	static private boolean isModified (File file, long lastModified) {
		if (file.lastModified() > lastModified) return true;
		File[] children = file.listFiles();
		if (children != null) {
			for (File child : children)
				if (isModified(child, lastModified)) return true;
		}
		return false;
	}

	static public boolean processIfModified (Settings settings, String input, String output, String packFileName, String[] additionalSuffixes, String[] additionalOutputs) {
		process(settings, input, output, packFileName, additionalSuffixes, additionalOutputs, null);
		return false;
	}

	static public interface Packer {
		public Array<Page> pack (Array<Rect> inputRects);

		/** @param progress May be null. */
		public Array<Page> pack (ProgressListener progress, Array<Rect> inputRects);
	}

	static final class InputImage {
		File file;
		String name;
		BufferedImage image;
	}

	static public void main (String[] args) throws Exception {
		Settings settings = null;
		String input = null, output = null, packFileName = "pack.atlas";

		String[] additionalFileSuffixes = null;
		String[] additionalOutputs = null;

		switch (args.length) {
		case 6:
			additionalOutputs = args[5].split(",");
		case 5:
			additionalFileSuffixes = args[4].split(",");
		case 4:
			settings = new Json().fromJson(Settings.class, new FileReader(args[3]));
		case 3:
			packFileName = args[2];
		case 2:
			output = args[1];
		case 1:
			input = args[0];
			break;
		default:
			System.out.println("Usage: inputDir [outputDir] [packFileName] [settingsFileName] [additionalSuffixes] [additionalOutputs]");
			System.exit(0);
		}

		if (output == null) {
			File inputFile = new File(input);
			output = new File(inputFile.getParentFile(), inputFile.getName() + "-packed").getAbsolutePath();
		}
		if (settings == null) settings = new Settings();

		processIfModified(settings, input, output, packFileName, additionalFileSuffixes, additionalOutputs);
	}
}
