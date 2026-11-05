// 'Corner frame' refers to a corner-local frame (a total of 4 of them are used per rrect) where
// the corner is at (0, 0) and the center of the corner arc's circle is at (r, r).

// Compute the y that corresponds to the given x of the circle arc in corner frame.
// The function is its own inverse (in x), so it also works in the other direction y -> x;
float rrectArcXYInCornerFrame(float x, float r) {
    return r - sqrt(x * (2.0 * r - x));
}

// Compute the 'corner deficit' of the box in corner frame.
// The corner deficit is the difference between the true coverage of that box by the rrect's corner
// against the coverage of the box by the same corner with r = 0 (sharp).
float rrectCornerDeficitInCornerFrame(vec2 boxMin, vec2 boxMax, float r) {
    boxMin = clamp(boxMin, 0.0, r);
    boxMax = clamp(boxMax, 0.0, r);

    // Early-return when the box is disjoint from [0, r]^2.
    if (boxMin.x == boxMax.x || boxMin.y == boxMax.y) return 0.0;

    // Compute the points where the arc enters/leaves the box.
    // If the arc never enters/leaves, these are clamped in a way that still gives correct results.
    vec2 arcEnter = clamp(vec2(rrectArcXYInCornerFrame(boxMax.y, r), rrectArcXYInCornerFrame(boxMin.x, r)), boxMin, boxMax);
    vec2 arcLeave = clamp(vec2(rrectArcXYInCornerFrame(boxMin.y, r), rrectArcXYInCornerFrame(boxMax.x, r)), boxMin, boxMax);

    // The rectangle where the whole arc is above the box (so the whole rectangle contributes to the deficit).
    float arcFullyAboveBoxDeficit = (arcEnter.x - boxMin.x) * (boxMax.y - boxMin.y);

    // Early-return on pixels that do not intersect the arc.
    // Somewhat improves performance on shapes like large circles,
    // but for small r does nothing because of warp divergence.
    if (arcEnter == arcLeave) return arcFullyAboveBoxDeficit;

    float trapezoidUnderArcArea = 0.5 * (arcLeave.x - arcEnter.x) * ((arcEnter.y - boxMin.y) + (arcLeave.y - boxMin.y));
    // The exact segment area is r^2 * (asin(q) - q * sqrt(1 - q^2)), where q := chord / 2r.
    // It's approximated as the first term of its Taylor series at 0:
    // asin(q) - q * sqrt(1 - q^2) = 2/3 * q^3 + 1/5 * q^5 + 3/28 * q^7 + ... ~= 2/3 * q^3,
    // and q^3 is rewritten as 1/8 * chord^3 / r.
    // Max error from this approximation:
    // * with r = 1, 12.7/255
    // * with r = 2, 1.21/255
    // * with r >= 3, <= 0.35/255
    float chord = distance(arcEnter, arcLeave);
    float arcSegmentArea = (1.0 / 12.0) * chord * chord * chord / r;
    float arcInsideBoxDeficit = trapezoidUnderArcArea - arcSegmentArea;

    return arcFullyAboveBoxDeficit + arcInsideBoxDeficit;
}

// Compute the coverage of a 1x1 box (the pixel) by the rrect.
float rrectBoxCoverage(vec2 boxCenter, vec2 rrectMin, vec2 rrectMax, float r) {
    vec2 boxMin = boxCenter - 0.5;
    vec2 boxMax = boxCenter + 0.5;

    // Step 1: compute the intersection between the box and the rrect assuming r = 0 (sharp corners).
    vec2 clampedBoxMin = clamp(boxMin, rrectMin, rrectMax);
    vec2 clampedBoxMax = clamp(boxMax, rrectMin, rrectMax);
    vec2 clampedBoxSize = clampedBoxMax - clampedBoxMin;
    float coverage = clampedBoxSize.x * clampedBoxSize.y;

    // Step 2: subtract the 'corner deficit' - the error introduces by assuming r = 0.
    if (r > 0.0) {
        [[unroll]] for (uint cornerIndexUint = 0; cornerIndexUint < 4; ++cornerIndexUint) {
            bvec2 cornerIndex = bvec2((cornerIndexUint & 1) != 0, (cornerIndexUint & 2) != 0);
            vec2 corner = mix(rrectMin, rrectMax, cornerIndex);
            vec2 boxRelativeToCornerMin = boxMin - corner;
            vec2 boxRelativeToCornerMax = boxMax - corner;

            // These are the two points boxInCornerFrameMin and boxInCornerFrameMax such that:
            // 1. They're opposite corners of the box in the corner frame.
            // 2. boxInCornerFrameMin.x <= boxInCornerFrameMax.x && boxInCornerFrameMin.y <= boxInCornerFrameMax.y.
            vec2 boxInCornerFrameMin = mix(boxRelativeToCornerMin, -boxRelativeToCornerMax, cornerIndex);
            vec2 boxInCornerFrameMax = mix(boxRelativeToCornerMax, -boxRelativeToCornerMin, cornerIndex);
            coverage -= rrectCornerDeficitInCornerFrame(boxInCornerFrameMin, boxInCornerFrameMax, r);
        }
    }

    return coverage;
}

float rrectOuterInnerBoxCoverageDifference(
    vec2 boxCenter,
    vec2 outerMin, vec2 outerMax, float outerRadius,
    vec2 innerMin, vec2 innerMax, float innerRadius
) {
    float coverage = rrectBoxCoverage(boxCenter, outerMin, outerMax, outerRadius);
    if (innerMin.x < innerMax.x && innerMin.y < innerMax.y) {
        coverage -= rrectBoxCoverage(boxCenter, innerMin, innerMax, innerRadius);
    }
    return clamp(coverage, 0.0, 1.0);
}
