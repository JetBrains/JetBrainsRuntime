// 'Unit box' here refers to the square [-0.5, +0.5]*[-0.5, +0.5].

// Compute the mean of clamp(lerp([x1, x2], t), -0.5, +0.5) for t from 0 to 1.
// In other words, this is the mean X value when linearly transitioning from x1 to x2 and clamping to the unit box.
float meanClampedX(float x1, float x2) {
    float dx = x2 - x1;
    if (dx == 0.0) return clamp(x1, -0.5, +0.5);

    float clampedX1 = clamp(x1, -0.5, +0.5);
    float clampedX2 = clamp(x2, -0.5, +0.5);

    float lengthBelow = min(x2, -0.5) - min(x1, -0.5);
    float lengthAbove = max(x2, +0.5) - max(x1, +0.5);

    // Compute the average from 3 separate pieces, weighted by piece length.
    // Below -0.5: average is -0.5, weight is lengthBelow;
    // Above +0.5: average is +0.5, weight is lengthAbove;
    // In between: average is 0.5 * (clampedX1 + clampedX2), weight is (clampedX2 - clampedX1).
    // So the total average is:
    // ( -0.5 * lengthBelow + 0.5 * lengthAbove + 0.5 * (clampedX1 + clampedX2) * (clampedX2 - clampedX1) ) / dx,
    // which is slightly rearranged here:
    return 0.5 * ((clampedX2 - clampedX1) * (clampedX1 + clampedX2) + (lengthAbove - lengthBelow)) / dx;
}

// Compute the signed area between the Y axis and the edge p1->p2 clamped to the unit box.
float signedEdgeAreaInsideUnitBox(vec2 p1, vec2 p2) {
    float clampedY1 = clamp(p1.y, -0.5, +0.5);
    float clampedY2 = clamp(p2.y, -0.5, +0.5);

    float dyClamped = clampedY2 - clampedY1;
    if (dyClamped == 0.0) return 0.0; // edge completely outside of the box's Y-range or a horizontal edge

    float dx = p2.x - p1.x;
    float dy = p2.y - p1.y;
    
    // Use the point closest in x as the anchor for computing intersections.
    vec2 anchor = abs(p1.x) <= abs(p2.x) ? p1 : p2;
    // Whenever clampedY1 != clampedY2, they're both provably in [min(y1, y2), max(y1, y2)],
    // so |t1| <= 1.0f and |t2| <= 1.0f, avoiding overflow on small dy.
    float t1 = (clampedY1 - anchor.y) / dy;
    float t2 = (clampedY2 - anchor.y) / dy;

    // The x coordinates at the y-clamped edge endpoints
    float xAtClampedY1 = anchor.x + t1 * dx;
    float xAtClampedY2 = anchor.x + t2 * dx;
    return dyClamped * meanClampedX(xAtClampedY1, xAtClampedY2);
}

float absParallelogramAreaInsideUnitBox(vec2 p1, vec2 d21, vec2 d12) {
    // Points are in cycle order here
    vec2 p2 = p1 + d21;
    vec2 p3 = p2 + d12;
    vec2 p4 = p1 + d12;
    return abs(
        signedEdgeAreaInsideUnitBox(p1, p2) +
        signedEdgeAreaInsideUnitBox(p2, p3) +
        signedEdgeAreaInsideUnitBox(p3, p4) +
        signedEdgeAreaInsideUnitBox(p4, p1)
    );
}

float parallelogramOuterInnerCoverageDifferenceInsideBox(
    vec2 boxCenter,
    vec2 outerOrigin, vec2 outerD21, vec2 outerD12,
    vec2 innerOrigin, vec2 innerD21, vec2 innerD12
) {
    return clamp(
        absParallelogramAreaInsideUnitBox(outerOrigin - boxCenter, outerD21, outerD12) -
        absParallelogramAreaInsideUnitBox(innerOrigin - boxCenter, innerD21, innerD12),
        0.0, 1.0
    );
}
