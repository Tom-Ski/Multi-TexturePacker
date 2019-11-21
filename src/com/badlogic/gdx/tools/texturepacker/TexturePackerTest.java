package com.badlogic.gdx.tools.texturepacker;

import com.badlogic.gdx.graphics.Texture;


public class TexturePackerTest {

	public static void main (String[] args) {

		Texture.TextureFilter minFilter = Texture.TextureFilter.Linear;
		Texture.TextureFilter magFilter = Texture.TextureFilter.Linear;

		TexturePacker.Settings settings = new TexturePacker.Settings();


		settings.combineSubdirectories = true;
		settings.maxWidth = 2048;
		settings.maxHeight = 2048;
		settings.edgePadding = true;
		settings.duplicatePadding = true;
		settings.filterMag = minFilter;
		settings.filterMin = magFilter;
		settings.paddingX = 2;
		settings.paddingY = 2;

		settings.stripWhitespaceX = true;
		settings.stripWhitespaceY = true;

		String rawsDir = "ship/";
		String targetDir = "out/";

		String atlas = "test-pack";

		String[] additionalSuffixes = {"_emissive"};
		String[] additionalOutputs = {"emissiveOut/"};

		MultiTexturePacker.process(settings, rawsDir, targetDir, atlas, additionalSuffixes, additionalOutputs, null);

	}

}
