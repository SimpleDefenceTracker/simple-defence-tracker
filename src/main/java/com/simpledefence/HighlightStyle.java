package com.simpledefence;

/** How a drained NPC is highlighted. */
public enum HighlightStyle
{
	HULL("Hull"),
	TILE("Tile"),
	TRUE_TILE("True tile"),
	OUTLINE("Outline");

	private final String name;

	HighlightStyle(String name)
	{
		this.name = name;
	}

	@Override
	public String toString()
	{
		return name;
	}
}
