/** Default theme settings */
export const themeSettings: App.Theme.ThemeSetting = {
  // Round 3 (light airy): light is the default theme; dark (Quiet Glass) stays fully available via the switcher
  themeScheme: 'light',
  grayscale: false,
  colourWeakness: false,
  recommendColor: false,
  themeColor: '#5856D6',
  otherColor: { info: '#5856D6', success: '#34C759', warning: '#FF9500', error: '#FF3B30' },
  isInfoFollowPrimary: true,
  resetCacheStrategy: 'close',
  layout: { mode: 'vertical', scrollMode: 'content', reverseHorizontalMix: false },
  page: { animate: true, animateMode: 'fade-slide' },
  header: { height: 52, breadcrumb: { visible: false, showIcon: true }, multilingual: { visible: false } },
  tab: { visible: false, cache: true, height: 44, mode: 'chrome' },
  fixedHeaderAndTab: true,
  sider: {
    inverted: false,
    width: 220,
    collapsedWidth: 64,
    mixWidth: 90,
    mixCollapsedWidth: 64,
    mixChildMenuWidth: 200
  },
  footer: { visible: false, fixed: false, height: 48, right: true },
  watermark: { visible: false, text: 'MindBase MindBase' },
  tokens: {
    light: {
      colors: {
        container: 'rgb(255, 255, 255)',
        layout: 'rgb(245, 245, 247)',
        inverted: 'rgb(29, 29, 31)',
        'base-text': 'rgb(29, 29, 31)'
      },
      boxShadow: {
        header: '0 0.5px 0 rgba(29, 29, 31, 0.12)',
        // Round 3: the sider detached from the window edges — it now carries the elevation-island
        // shadow instead of the old inset hairline (DESIGN.md › Elevation › elevation-island)
        sider: '0 1px 2px rgba(0, 0, 0, 0.04), 0 12px 32px rgba(0, 0, 0, 0.10)',
        tab: 'none'
      }
    },
    dark: {
      colors: {
        container: 'rgb(28, 28, 30)',
        layout: 'rgb(10, 10, 12)',
        inverted: 'rgb(245, 245, 247)',
        'base-text': 'rgb(245, 245, 247)'
      },
      boxShadow: {
        header: '0 0.5px 0 rgba(235, 235, 245, 0.14)',
        // Island shadow in dark theme too — same silhouette, heavier ink (DESIGN.md › elevation-island)
        sider: '0 1px 2px rgba(0, 0, 0, 0.45), 0 12px 32px rgba(0, 0, 0, 0.55)',
        tab: 'none'
      }
    }
  }
};

/**
 * Override theme settings
 *
 * If publish new version, use `overrideThemeSettings` to override certain theme settings
 */
export const overrideThemeSettings: Partial<App.Theme.ThemeSetting> = {
  // Quiet Glass redesign: push the new color/shell tokens to sessions with cached settings.
  // themeScheme must ride this channel too: dev reads themeSettings directly, but prod sessions
  // keep a cached copy where defu would preserve their old 'auto' — the override forces 'light'.
  themeScheme: 'light',
  themeColor: themeSettings.themeColor,
  otherColor: themeSettings.otherColor,
  recommendColor: themeSettings.recommendColor,
  header: { ...themeSettings.header },
  sider: { ...themeSettings.sider },
  tokens: themeSettings.tokens
};
