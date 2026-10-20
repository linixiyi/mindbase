import type { GlobalThemeOverrides } from 'naive-ui';
import { defu } from 'defu';
import { addColorAlpha, getColorPalette, getPaletteColorByNumber, getRgb } from '@sa/color';
import { DARK_CLASS } from '@/constants/app';
import { toggleHtmlClass } from '@/utils/common';
import { localStg } from '@/utils/storage';
import { overrideThemeSettings, themeSettings } from '@/theme/settings';
import { themeVars } from '@/theme/vars';

/** Init theme settings */
export function initThemeSettings() {
  const isProd = import.meta.env.PROD;

  // if it is development mode, the theme settings will not be cached, by update `themeSettings` in `src/theme/settings.ts` to update theme settings
  if (!isProd) return themeSettings;

  // if it is production mode, the theme settings will be cached in localStorage
  // if want to update theme settings when publish new version, please update `overrideThemeSettings` in `src/theme/settings.ts`

  const localSettings = localStg.get('themeSettings');

  let settings = defu(localSettings, themeSettings);

  const isOverride = localStg.get('overrideThemeFlag') === BUILD_TIME;

  if (!isOverride) {
    settings = defu(overrideThemeSettings, settings);

    localStg.set('overrideThemeFlag', BUILD_TIME);
  }

  return settings;
}

/**
 * create theme token css vars value by theme settings
 *
 * @param colors Theme colors
 * @param tokens Theme setting tokens
 * @param [recommended=false] Use recommended color. Default is `false`
 */
export function createThemeToken(
  colors: App.Theme.ThemeColor,
  tokens?: App.Theme.ThemeSetting['tokens'],
  recommended = false
) {
  const paletteColors = createThemePaletteColors(colors, recommended);

  const { light, dark } = tokens || themeSettings.tokens;

  const themeTokens: App.Theme.ThemeTokenCSSVars = {
    colors: {
      ...paletteColors,
      nprogress: paletteColors.primary,
      ...light.colors
    },
    boxShadow: {
      ...light.boxShadow
    }
  };

  const darkThemeTokens: App.Theme.ThemeTokenCSSVars = {
    colors: {
      ...themeTokens.colors,
      ...dark?.colors
    },
    boxShadow: {
      ...themeTokens.boxShadow,
      ...dark?.boxShadow
    }
  };

  return {
    themeTokens,
    darkThemeTokens
  };
}

/**
 * Create theme palette colors
 *
 * @param colors Theme colors
 * @param [recommended=false] Use recommended color. Default is `false`
 */
function createThemePaletteColors(colors: App.Theme.ThemeColor, recommended = false) {
  const colorKeys = Object.keys(colors) as App.Theme.ThemeColorKey[];
  const colorPaletteVar = {} as App.Theme.ThemePaletteColor;

  colorKeys.forEach(key => {
    const colorMap = getColorPalette(colors[key], recommended);

    colorPaletteVar[key] = colorMap.get(500)!;

    colorMap.forEach((hex, number) => {
      colorPaletteVar[`${key}-${number}`] = hex;
    });
  });

  return colorPaletteVar;
}

/**
 * Get css var by tokens
 *
 * @param tokens Theme base tokens
 */
function getCssVarByTokens(tokens: App.Theme.BaseToken) {
  const styles: string[] = [];

  function removeVarPrefix(value: string) {
    return value.replace('var(', '').replace(')', '');
  }

  function removeRgbPrefix(value: string) {
    return value.replace('rgb(', '').replace(')', '');
  }

  for (const [key, tokenValues] of Object.entries(themeVars)) {
    for (const [tokenKey, tokenValue] of Object.entries(tokenValues)) {
      let cssVarsKey = removeVarPrefix(tokenValue);
      let cssValue = tokens[key][tokenKey];

      if (key === 'colors') {
        cssVarsKey = removeRgbPrefix(cssVarsKey);
        const { r, g, b } = getRgb(cssValue);
        cssValue = `${r} ${g} ${b}`;
      }

      styles.push(`${cssVarsKey}: ${cssValue}`);
    }
  }

  const styleStr = styles.join(';');

  return styleStr;
}

/**
 * Add theme vars to global
 *
 * @param tokens
 */
export function addThemeVarsToGlobal(tokens: App.Theme.BaseToken, darkTokens: App.Theme.BaseToken) {
  const cssVarStr = getCssVarByTokens(tokens);
  const darkCssVarStr = getCssVarByTokens(darkTokens);

  const css = `
    :root {
      ${cssVarStr}
    }
  `;

  const darkCss = `
    html.${DARK_CLASS} {
      ${darkCssVarStr}
    }
  `;

  const styleId = 'theme-vars';

  const style = document.querySelector(`#${styleId}`) || document.createElement('style');

  style.id = styleId;

  style.textContent = css + darkCss;

  document.head.appendChild(style);
}

/**
 * Toggle css dark mode
 *
 * @param darkMode Is dark mode
 */
export function toggleCssDarkMode(darkMode = false) {
  const { add, remove } = toggleHtmlClass(DARK_CLASS);

  if (darkMode) {
    add();
  } else {
    remove();
  }
}

/**
 * Toggle auxiliary color modes
 *
 * @param grayscaleMode
 * @param colourWeakness
 */
export function toggleAuxiliaryColorModes(grayscaleMode = false, colourWeakness = false) {
  const htmlElement = document.documentElement;
  htmlElement.style.filter = [grayscaleMode ? 'grayscale(100%)' : '', colourWeakness ? 'invert(80%)' : '']
    .filter(Boolean)
    .join(' ');
}

type NaiveColorScene = '' | 'Suppl' | 'Hover' | 'Pressed' | 'Active';
type NaiveColorKey = `${App.Theme.ThemeColorKey}Color${NaiveColorScene}`;
type NaiveThemeColor = Partial<Record<NaiveColorKey, string>>;
interface NaiveColorAction {
  scene: NaiveColorScene;
  handler: (color: string) => string;
}

/**
 * Get naive theme colors
 *
 * @param colors Theme colors
 * @param [_recommended=false] Deprecated: kept for signature compatibility, hover/pressed now derive from fixed 600/700
 *   steps. Default is `false`
 */
function getNaiveThemeColors(colors: App.Theme.ThemeColor, _recommended = false) {
  const colorActions: NaiveColorAction[] = [
    { scene: '', handler: color => color },
    { scene: 'Suppl', handler: color => color },
    // With `recommendColor: false` the 500 step equals the base color, so hover/pressed
    // derive from the 600/700 steps of the *active* base color (dark theme swaps automatically).
    { scene: 'Hover', handler: color => getPaletteColorByNumber(color, 600, false) },
    { scene: 'Pressed', handler: color => getPaletteColorByNumber(color, 700, false) },
    { scene: 'Active', handler: color => addColorAlpha(color, 0.1) }
  ];

  const themeColors: NaiveThemeColor = {};

  const colorEntries = Object.entries(colors) as [App.Theme.ThemeColorKey, string][];

  colorEntries.forEach(color => {
    colorActions.forEach(action => {
      const [colorType, colorValue] = color;
      const colorKey: NaiveColorKey = `${colorType}Color${action.scene}`;
      themeColors[colorKey] = action.handler(colorValue);
    });
  });

  return themeColors;
}

/** System font stack of the Quiet Glass world (SF Pro + PingFang SC on macOS) */
const appFontFamily =
  '-apple-system, BlinkMacSystemFont, "SF Pro Text", "SF Pro Display", "Segoe UI", "Helvetica Neue", "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei UI", "Microsoft YaHei", sans-serif';

/** Mono font stack of the Quiet Glass world */
const appFontFamilyMono = 'ui-monospace, "SF Mono", Menlo, Consolas, "Liberation Mono", monospace';

/**
 * Get naive theme
 *
 * @param colors Theme colors
 * @param [recommended=false] Use recommended color. Default is `false`
 */
export function getNaiveTheme(colors: App.Theme.ThemeColor, recommended = false) {
  const { primary: colorLoading } = colors;

  const theme: GlobalThemeOverrides = {
    common: {
      ...getNaiveThemeColors(colors, recommended),
      fontFamily: appFontFamily,
      fontFamilyMono: appFontFamilyMono,
      fontWeight: '400',
      fontWeightStrong: '600',
      lineHeight: '1.65',
      fontSize: '14px',
      fontSizeSmall: '13px',
      heightSmall: '28px',
      heightMedium: '36px',
      heightLarge: '44px',
      borderRadius: '10px',
      borderRadiusSmall: '6px',
      boxShadow1: 'var(--app-shadow-e1)',
      boxShadow2: 'var(--app-shadow-e2)',
      boxShadow3: 'var(--app-shadow-e3)',
      cubicBezierEaseInOut: 'var(--app-ease-standard)',
      cubicBezierEaseOut: 'var(--app-ease-emphasized)',
      cubicBezierEaseIn: 'var(--app-ease-exit)'
    },
    LoadingBar: {
      colorLoading
    },
    Button: {
      fontWeight: '600'
    },
    Input: {
      boxShadowFocus: '0 0 0 3px rgb(var(--primary-color) / 0.2)'
    },
    Menu: {
      itemHeight: '36px',
      borderRadius: '10px',
      fontSize: '14px',
      itemColorHover: 'var(--app-fill-hover)',
      itemColorActive: 'var(--app-fill-subtle)',
      itemColorActiveHover: 'var(--app-fill-hover)',
      itemColorActiveCollapsed: 'var(--app-fill-subtle)',
      itemTextColor: 'var(--app-text-secondary)',
      itemTextColorHover: 'rgb(var(--base-text-color))',
      itemTextColorActive: 'rgb(var(--base-text-color))',
      itemTextColorActiveHover: 'rgb(var(--base-text-color))',
      itemTextColorChildActive: 'rgb(var(--primary-color))',
      itemTextColorChildActiveHover: 'rgb(var(--primary-color))',
      itemIconColor: 'var(--app-text-secondary)',
      itemIconColorHover: 'rgb(var(--base-text-color))',
      itemIconColorActive: 'rgb(var(--primary-color))',
      itemIconColorActiveHover: 'rgb(var(--primary-color))',
      itemIconColorChildActive: 'rgb(var(--primary-color))',
      itemIconColorChildActiveHover: 'rgb(var(--primary-color))',
      arrowColor: 'var(--app-text-tertiary)',
      arrowColorHover: 'rgb(var(--base-text-color))',
      arrowColorActive: 'rgb(var(--primary-color))',
      arrowColorActiveHover: 'rgb(var(--primary-color))',
      arrowColorChildActive: 'rgb(var(--primary-color))',
      arrowColorChildActiveHover: 'rgb(var(--primary-color))',
      groupTextColor: 'var(--app-text-tertiary)'
    },
    Tag: {
      borderRadius: '9999px'
    },
    Card: {
      borderRadius: '18px'
    },
    Dialog: {
      borderRadius: '18px'
    },
    Drawer: {
      borderRadius: '18px'
    }
  };

  return theme;
}
