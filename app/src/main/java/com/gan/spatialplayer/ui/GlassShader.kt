package com.gan.spatialplayer.ui

/** Shared optical model for video-backed and window-backed glass. */
internal object GlassShader {
        /**
         * Fragment shader for the glass.
         *
         * `sdRoundRect` gives a signed distance field, whose gradient is the surface normal - that
         * is what drives refraction. Sampling the backdrop three times with slightly different
         * offsets produces the chromatic fringe, and the caustic term is advected by the same
         * offset so the highlights move with the refraction rather than sliding independently.
         */
        val SOURCE = """
            uniform float2 uSize;
            uniform float  uRadius;
            uniform float  uBevel;
            uniform float  uRefract;
            uniform float  uFalloff;
            uniform float  uDispersion;
            uniform float2 uLightDir;
            uniform float  uSpec;
            uniform float  uSaturation;
            uniform vec4   uTint;
            uniform vec4   uGlassTint;
            uniform float  uDim;
            uniform float  uPress;          // 0..1, how far into a press the surface is
            uniform float2 uTouchPos;       // finger position in pane coordinates
            uniform float  uTouchAmp;       // 0..1, whether the finger is on the pane at all
            uniform float  uPressBulge;     // local magnification under the finger, vs rim refraction
            uniform float  uBlur;           // frost radius, in backdrop texels
            uniform float2 uBackdropOrigin; // view px, top-left of the captured rectangle
            uniform float2 uBackdropScale;  // view px -> backdrop texel
            uniform float2 uBackdropTexSize;

            uniform shader uBackdrop;

            // A sample inside the captured rectangle, or zero outside it. The pane is usually wider
            // than the picture, and the texture's clamped edge would otherwise be smeared across it.
            half4 backdropMask(float2 t) {
                float2 lo = step(float2(0.0), t);
                float2 hi = step(t, max(uBackdropTexSize - float2(1.0, 1.0), float2(0.0)));
                return half4(half(lo.x * lo.y * hi.x * hi.y));
            }

            half4 backdropAt(float2 t) {
                float2 texMax = max(uBackdropTexSize - float2(1.0, 1.0), float2(0.0));
                return uBackdrop.eval(clamp(t, float2(0.0), texMax)) * backdropMask(t);
            }

            /**
             * Frost.
             *
             * This is the pass the first version never had: the backdrop was sampled once per channel
             * and only *bent*, so the glass had a lens in it and no diffusion - crisp content behind a
             * warped edge, which reads as a funhouse mirror rather than as frosted glass. Thirteen taps
             * on two rings is the cheapest kernel that looks like diffusion at this blur radius; the
             * backdrop is small (<= 768 texels on its long edge) and the pane is a fraction of the
             * screen, so the cost is paid on a few thousand fragments.
             */
            half4 frost(float2 t, float radius) {
                if (radius < 0.35) return backdropAt(t);
                half4 sum = backdropAt(t) * half(0.10);
                // Inner ring: six points on a circle, so the kernel is round rather than boxy.
                for (int i = 0; i < 6; i++) {
                    float a = float(i) * 1.04719755; // 60 degrees
                    float2 d = float2(cos(a), sin(a)) * radius * 0.55;
                    sum += backdropAt(t + d) * half(0.10);
                }
                // Outer ring, half the weight: this is what makes the falloff read as a soft cloud
                // instead of a uniform smear.
                for (int i = 0; i < 6; i++) {
                    float a = float(i) * 1.04719755 + 0.52359878; // 60 degrees, offset 30
                    float2 d = float2(cos(a), sin(a)) * radius;
                    sum += backdropAt(t + d) * half(0.05);
                }
                return sum;
            }

            float sdRoundRect(float2 p, float2 halfSize, float r) {
                float2 q = abs(p) - halfSize + float2(r);
                return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;
            }

            half4 main(float2 coord) {
                float2 halfSize = uSize * 0.5;
                float2 p = coord - halfSize;
                float d = sdRoundRect(p, halfSize, uRadius);

                if (d > 0.0) {
                    return half4(0.0);
                }

                // Outward normal of the rounded rect, from the same distance field the silhouette
                // uses, so refraction and rim light cannot disagree about where the edge is.
                float2 n = normalize(float2(
                    sdRoundRect(p + float2(1.0, 0.0), halfSize, uRadius) - d,
                    sdRoundRect(p + float2(0.0, 1.0), halfSize, uRadius) - d
                ));

                // Bevel profile: everything happens in the last uBevel pixels, concentrated outward.
                float edge = clamp(-d / max(uBevel, 1.0), 0.0, 1.0);
                float profile = uFalloff > 0.0
                    ? pow(edge, uFalloff)
                    : edge * edge;
                float bend = (1.0 - profile) * uRefract * (1.0 + 0.35 * uPress);

                // The finger pushes a lens into the surface. This is the "liquid" half of the press:
                // refraction alone strengthens the whole rim, which reads as the pane getting
                // glossier, not as being pressed. A local bulge under the touch point samples towards
                // the finger, so the backdrop magnifies exactly where the finger is - and it is
                // weighted by uTouchAmp, which is zero unless that finger is down.
                if (uTouchAmp > 0.001) {
                    float2 tp = p - uTouchPos;
                    float tr = length(tp);
                    float sigma = max(max(halfSize.x, halfSize.y), 1.0);
                    float bump = uTouchAmp * exp(-(tr * tr) / (sigma * sigma * 0.30));
                    bend += bump * uRefract * uPressBulge;
                }

                // The blur grows towards the rim, which is how a real bevelled edge behaves: thick
                // glass diffuses more where the light path is longest.
                float frostRadius = uBlur * (0.55 + 0.45 * profile);

                float2 cG = coord - n * bend;
                float2 tG = (cG - uBackdropOrigin) * uBackdropScale;

                half4 base = frost(tG, frostRadius);

                // Dispersion: red and blue bend slightly more and less than green.
                float2 cR = coord - n * bend * (1.0 + uDispersion);
                float2 cB = coord - n * bend * (1.0 - uDispersion);
                half r = frost((cR - uBackdropOrigin) * uBackdropScale, frostRadius).r;
                half b = frost((cB - uBackdropOrigin) * uBackdropScale, frostRadius).b;
                half3 refracted = half3(r, base.g, b);

                // Vibrance.
                half luma = dot(refracted, half3(0.2126, 0.7152, 0.0722));
                half3 col = mix(half3(luma), refracted, half(uSaturation));

                // Body tint: absorption plus a little scattering.
                col = mix(col, half3(half(uGlassTint.r), half(uGlassTint.g), half(uGlassTint.b)),
                          half(uGlassTint.a));
                col = mix(col, half3(0.0), half(uDim));
                col = mix(col, half3(half(uTint.r), half(uTint.g), half(uTint.b)), half(uTint.a));

                // Rim light from the same normal field: two symmetric lobes, so the lit and shadow
                // sides peak equally and no direction-independent outline is left behind. Pressing
                // tightens the lobe towards the rim and dims it slightly, which is what a thumb
                // resting on glass does to the highlight over it.
                float ndl = dot(n, normalize(uLightDir));
                float lit = pow(max(ndl, 0.0), 4.5 + 1.6 * uPress);
                float shadow = pow(max(-ndl, 0.0), 4.5);
                float rim = (lit - shadow * 0.55) * uSpec * (1.0 - 0.35 * uPress) * profile;
                float inner = lit * 0.25 * uSpec * pow(edge, 0.6);
                col += half3(half(max(rim, 0.0) + max(inner, 0.0)));

                // A hairline on the silhouette, and a straight-alpha edge for antialiasing.
                float cov = 1.0 - smoothstep(-1.2, 0.0, d);
                col += half3(half(0.06 * uSpec * (1.0 - profile)));

                return half4(half3(col * half(cov)), half(cov));
            }
        """.trimIndent()
}
