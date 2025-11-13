/*
 * Copyright (C) 2025 Peter Paul Bakker - Rabobank
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.rabobank.cme.rps;

import java.security.SecureRandom;

public class RandomRPS implements RequestsPerSecond {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final int min;
    private final int max;
    private final int extra;

    /**
     * Constructor for RandomRPS.
     *
     * @param min   the minimum value for random number generation
     * @param max   the maximum value for random number generation
     * @param extra the extra value to add if the current minute is odd
     */
    public RandomRPS(int min, int max, int extra) {
        this.min = min;
        this.max = max;
        this.extra = extra;
    }

    @Override
    public int rps() {
        int rps = SECURE_RANDOM.nextInt(max - min + 1) + min;
        int currentMinute = java.time.LocalDateTime.now().getMinute();
        if (currentMinute % 2 != 0) {
            rps += extra;
        }
        return rps;
    }
}
