package com.commander4j.modbusbridge;

/**
 * One {@code <id>} entry from the optional {@code <restapi>} config section: an
 * application-facing name mapped onto a configured point. The name is the caller's
 * vocabulary (e.g. the production lane id {@code LANEA} used by c4j_web_Issue); the point
 * is the physical wiring (e.g. relay coil {@code PLC_Link1}).
 *
 * <p>Several ids may map to the same point — that is the purpose: REST calls made under
 * any of them resolve to <em>one</em> {@link ModbusPoint}, so pulses fired via different
 * ids serialise on that point's single queue instead of interfering with each other's
 * hold/reset cycle. Point names themselves stay addressable alongside the ids.
 *
 * <p>{@code caseSensitive} (attribute {@code caseSensitive}; absent → {@code false})
 * controls matching: an insensitive id matches any capitalisation, and config validation
 * reserves its whole case class so no other point name or id can collide with it.
 */
public record RestApiId(String name, String point, boolean caseSensitive)
{
	public RestApiId
	{
		if (name == null || name.isBlank())
		{
			throw new IllegalArgumentException("restapi id name must not be blank");
		}
		if (point == null || point.isBlank())
		{
			throw new IllegalArgumentException("restapi id '" + name + "' has no point attribute");
		}
	}
}
