package com.snowfox;

import java.util.UUID;

record Pending(UUID owner, long expires) {}
