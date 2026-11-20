// vitest 全局 setup（jsdom 环境在 vite.config.ts 中配置）

import { config } from '@vue/test-utils'
import { defineComponent, h } from 'vue'

// 各 spec 会 mock vue-router 以隔离路由，mock 里只给 useRoute/useRouter，于是模板里的
// <router-link> 解析不到组件，每条用例都刷一串 "Failed to resolve component" 警告，
// 把真正的失败信息挤出输出。这里统一注册一个渲染成 <a> 的替身，链接文字照常渲染；
// 需要断言跳转的用例仍然自己传 stubs 覆盖。
config.global.stubs = {
  RouterLink: defineComponent({
    name: 'RouterLink',
    inheritAttrs: false,
    props: { to: { type: [String, Object], default: '' } },
    setup(props, { slots, attrs }) {
      return () =>
        h(
          'a',
          { ...attrs, href: typeof props.to === 'string' && props.to ? props.to : '#' },
          slots.default?.(),
        )
    },
  }),
}
