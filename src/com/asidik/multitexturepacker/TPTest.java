package com.asidik.multitexturepacker;

public class TPTest {

	public static void main (String[] args) {
		TexturePacker.Settings settings = new TexturePacker.Settings();
		TexturePacker.process(settings, "diffuseIn", "diffuseOut", "game", new String[]{"emissiveIn"}, new String[] {"_emissive"}, new String[] {"emissiveOut"}, null);
	}
}
