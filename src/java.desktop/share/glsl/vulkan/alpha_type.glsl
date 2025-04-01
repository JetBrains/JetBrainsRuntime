
#define ALPHA_TYPE_PRE_MULTIPLIED 0U
#define ALPHA_TYPE_STRAIGHT       1U

vec4 convertAlpha(vec4 color, uint inType, uint outType) {
    if (inType == ALPHA_TYPE_STRAIGHT && outType == ALPHA_TYPE_PRE_MULTIPLIED) {
        return vec4(color.rgb * color.a, color.a);
    } else if (inType == ALPHA_TYPE_PRE_MULTIPLIED && outType == ALPHA_TYPE_STRAIGHT && color.a > 0.0) {
        return vec4(color.rgb / color.a, color.a);
    } else return color;
}

#ifdef ALPHA_TYPE_SPEC_INDEX
layout (constant_id = ALPHA_TYPE_SPEC_INDEX  ) const uint const_InAlphaType  = ALPHA_TYPE_PRE_MULTIPLIED;
layout (constant_id = ALPHA_TYPE_SPEC_INDEX+1) const uint const_OutAlphaType = ALPHA_TYPE_PRE_MULTIPLIED;

vec4 convertAlpha(vec4 color) {
    return convertAlpha(color, const_InAlphaType, const_OutAlphaType);
}
#endif
