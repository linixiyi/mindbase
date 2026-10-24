/** Default theme settings */
export const themeSettings: App.Theme.ThemeSetting = {
  // Round 3 (light airy): light is the default theme; dark (Quiet Glass) stays fully available via the switcher
  themeScheme: 'light',
  grayscale: false,
  colourWeakness: false,
  recommendColor: false,
  // Round 4 (Azure): user brief is a light-blue accent — apple.com button blue #0071E3
  // (white-text contrast 4.70:1, AA); hover/pressed derive from palette 600/700 (#0058BD/#004196)
  themeColor: '#0071E3',
  otherColor: { info: '#0071E3', success: '#34C759', warning: '#FF9500', error: '#FF3B30' },
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
        // Round 4 (Azure): cool blue-tinted neutrals — layout ground, ink and hairline carry a
        // 210° blue cast so the light theme reads "light blue" without extra accent paint
        layout: 'rgb(242, 246, 252)',
        inverted: 'rgb(28, 42, 58)',
        'base-text': 'rgb(28, 42, 58)'
      },
      boxShadow: {
        // R12 overdrive: the 0.5px header hairline left the shadow pipeline — it is now a real
        // element in global-header whose opacity is scroll-revealed (DESIGN.md §18), colored by
        // --app-header-hairline in global.scss. Token retired to none like tab (vars.ts keeps
        // generating --header-box-shadow; the shadow-header consumer is gone with it).
        header: 'none',
        // Round 3: the sider detached from the window edges — it now carries the elevation-island
        // shadow instead of the old inset hairline (DESIGN.md › Elevation › elevation-island).
        // R8 bolder: ambient layer widened and faded (12/32 → 16/48 daylight pool), contact kept
        // tight — mirrors --app-shadow-island in global.scss (same values, two declared homes)
        sider: '0 1px 2px rgba(0, 0, 0, 0.05), 0 16px 48px rgba(0, 0, 0, 0.08)',
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
        // R12 overdrive: dark hairline rides the same scroll-revealed element; token retired (see light note)
        header: 'none',
        // Island shadow in dark theme too — same silhouette, heavier ink (DESIGN.md › elevation-island);
        // R8 bolder: ambient widened to 16/48 and eased one step (0.55 → 0.50) so the ink pool
        // spreads instead of deepening — dark depth still leans on the surface steps
        sider: '0 1px 2px rgba(0, 0, 0, 0.45), 0 16px 48px rgba(0, 0, 0, 0.50)',
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
