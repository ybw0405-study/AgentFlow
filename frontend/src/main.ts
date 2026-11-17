import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { wireSessionHooks } from './stores/auth'
import './design/base.css'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/controls/dist/style.css'

wireSessionHooks(() => {
  void router.push({ name: 'login' })
})

createApp(App).use(createPinia()).use(router).mount('#app')
