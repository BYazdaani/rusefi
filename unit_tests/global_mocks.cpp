
#include "pch.h"

#include "trigger_central.h"
#include "map_resize.h"

bool verboseMode = false;

int timeNowUs = 0;

efitimeus_t getTimeNowUs(void) {
	return timeNowUs;
}

efitick_t getTimeNowNt(void) {
	return getTimeNowUs() * US_TO_NT_MULTIPLIER;
}

void initLogging(LoggingWithStorage *logging, const char *name) {
}
