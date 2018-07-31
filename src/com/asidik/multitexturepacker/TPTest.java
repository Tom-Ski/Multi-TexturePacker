package com.asidik.multitexturepacker;

public class TPTest {

	public static void main (String[] args) {
		MultiTexturePacker.Settings settings = new MultiTexturePacker.Settings();
		MultiTexturePacker
			.process(settings, "diffuseIn", "diffuseOut", "game", new String[]{"emissiveIn"}, new String[] {"_emissive"}, new String[] {"emissiveOut"}, null);
	}
}
