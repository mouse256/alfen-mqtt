# basics

This application controls an alfen Eve wallbox, to charge an electric car optimal.

For input, it uses
* Data from the alfen wallbox, over modbus TCP
* Data over MQTT
  * power consumed/produced to the grid. There's no need to measure the solar panels separately. The exported/imported to/from the grid tells enough.
  * control commands the change some behavior (sent from other applications)

# alfen

The alfen wallbox is controlled over modbus TCP. There is a file `configuration_guide_modbus_ace_v1.pdf` 
in this repo with all the modbus commands documented.
Important to know: this application must actively keep on sending commands to the wallbox, otherwise it goes into a
"safe mode" if nothing is actively controlling it anymore

The main control happens by sending a "desired current" to the car, the speed at which is should charge.
Important here is: The minimum value the car supports is 6A (which is roughly 1400W) per phase. The max is 16A per phase.
So the only valid values are either 0, or a number between 6 and 16 for charging current (per phase)

Also important: the charger must not constantly change between on and off. There should be debounce timers to avoid changing
modes too frequently.

# MQTT

Control happens over MQTT.
Statistics/messages are published over MQTT.
Control commands are sent over MQTT

The main consumer of this is HomeAssistant, so there should be homeassistant discovery messages if possible.

# control modes

The very first basic rule: in any mode, when the car is charging, it MUST NEVER consume too much power from the grid.
This setting is configured in `writer.max-grid-power` in the settings. This always takes priority.

## OFF

That's easy, just not charging.

## PV_ONLY

The aim is to get the power imported/exported to the grid as close to 0 as possible. So consume the power produced by
the solar panels.
The tricky part is that the minimum current for the car is 6A. Which means a choice has to be made: how much power
do you allow to be exported to the grid before starting to charge? This is controlled by `writer.solar-start-power`
and `writer.solar-stop-power`. 
Start: controlled by `writer.solar-start-power`. If there is more than that value exported to the grid 
for `writer.start-delay`, the car should start charging, even if that means importing the rest from the grid.
Stop: controlled by `writer.solar-stop-power`. If the import from the grid is above this value for `writer.stop-delay`,
the car should stop charging.

## PV_AND_MIN

Here the goal is to ensure the car always charges slowly, but faster if there is excess power from the solar panels.

So it should charge at least at 6A all the time (unless the grid import is at capacity, see the hard requirement above)
However if (and only if) there would be (after already charging with 6A) still power exported to the grid, the car 
should charge at higher speed, to ensure there is no power exported to the grid.

## FAST

It should charge as fast as possible.
The main limitation is the `writer.max-grid-power`. So it can charge the car as fast as possible, but keeping the power
imported from the grid below this level.

## SMART

This mode should work as following.
If the battery of the car is below 40pct (configurable), it should do the same as the PV_AND_MIN mode,
to ensure the car always gets to 40pct battery.

Above 40pct battery, it switches to PV_ONLY alike.
However, it should be dynamic on the amount of power it will use from the grid.
If the battery is low (so starting at 40pct), it should start with a lot of power from the grid already. 
So for example as soon as there is 300W excess from the solar panels (so 1000 from the grid) is should start already.
The more the battery gets charged (so till 100pct), the more solar power it needs to start (also configurable).
It should be a linear interpolation in between