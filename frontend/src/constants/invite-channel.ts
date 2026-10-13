export const inviteChannelConfig = {
  officialAccountName: 'MindBase 管理员',
  replyKeywords: ['邀请码'],
  qrCodeImageUrl: ''
} as const;

export function buildInviteChannelGuide() {
  return [
    '获取 MindBase 邀请码方式：',
    `1. 联系${inviteChannelConfig.officialAccountName}领取`,
    '2. 收到邀请码后，回到注册页继续完成注册'
  ].join('\n');
}

export function buildInviteCodeShareMessage(shareLink: string, inviteCode: string) {
  return [
    'MindBase 正在内测，欢迎来体验。',
    `邀请码：${inviteCode}`,
    `注册链接：${shareLink}`,
    '',
    `如果邀请码失效，可以联系${inviteChannelConfig.officialAccountName}重新领取。`
  ].join('\n');
}
