import { defineConfig } from '@unocss/vite';
import transformerDirectives from '@unocss/transformer-directives';
import transformerVariantGroup from '@unocss/transformer-variant-group';
import presetWind3 from '@unocss/preset-wind3';
import type { Theme } from '@unocss/preset-uno';
import { presetSoybeanAdmin } from '@sa/uno-preset';
import { themeVars } from './src/theme/vars';

export default defineConfig<Theme>({
  content: {
    pipeline: {
      exclude: ['node_modules', 'dist']
    }
  },
  theme: {
    ...themeVars,
    colors: {
      ...themeVars.colors,
      // Quiet Glass token aliases (values declared in styles/scss/global.scss)
      elevated: 'var(--app-elevated)',
      hairline: 'var(--app-border)',
      'fill-subtle': 'var(--app-fill-subtle)',
      'fill-hover': 'var(--app-fill-hover)',
      secondary: 'var(--app-text-secondary)',
      tertiary: 'var(--app-text-tertiary)'
    },
    fontSize: {
      'icon-xs': '0.875rem',
      'icon-small': '1rem',
      icon: '1.125rem',
      'icon-large': '1.5rem',
      'icon-xl': '2rem'
    }
  },
  shortcuts: {
    // Static panel: opaque container surface, hairline border, zero shadow
    'card-wrapper': 'rounded-18px border border-solid border-hairline shadow-none',
    // Frosted navigation material (shell & floating layers only, never reading surfaces)
    'glass-nav':
      'bg-[var(--app-material-nav)] [backdrop-filter:var(--app-material-filter)] [-webkit-backdrop-filter:var(--app-material-filter)]',
    'flex-cc': 'flex items-center justify-center'
  },
  transformers: [transformerDirectives(), transformerVariantGroup()],
  presets: [presetWind3({ dark: 'class' }), presetSoybeanAdmin()]
});
