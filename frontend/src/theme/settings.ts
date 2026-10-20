/** Default theme settings */
export const themeSettings: App.Theme.ThemeSetting = {
  themeScheme: 'auto',
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
        sider: 'inset -0.5px 0 0 rgba(29, 29, 31, 0.12)',
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
        sider: 'inset -0.5px 0 0 rgba(235, 235, 245, 0.14)',
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
  // Quiet Glass redesign: push the new color/shell tokens to sessions with cached settings
  themeColor: themeSettings.themeColor,
  otherColor: themeSettings.otherColor,
  recommendColor: themeSettings.recommendColor,
  header: { ...themeSettings.header },
  sider: { ...themeSettings.sider },
  tokens: themeSettings.tokens
};
