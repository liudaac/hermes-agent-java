import { createRouter, createWebHistory } from 'vue-router'
import Layout from '../components/Layout.vue'

// 原有页面
import ChatView from '../views/ChatView.vue'
import TenantView from '../views/TenantView.vue'

// 新增管理后台页面 (从 web/ 迁移)
import DashboardView from '../views/admin/DashboardView.vue'
import SessionsView from '../views/admin/SessionsView.vue'
import AnalyticsView from '../views/admin/AnalyticsView.vue'
import LogsView from '../views/admin/LogsView.vue'
import CronView from '../views/admin/CronView.vue'
import SkillsView from '../views/admin/SkillsView.vue'
import ConfigView from '../views/admin/ConfigView.vue'
import EnvView from '../views/admin/EnvView.vue'

const routes = [
  {
    path: '/',
    component: Layout,
    children: [
      // 用户端 - Agent 交互
      {
        path: '',
        name: 'Chat',
        component: ChatView,
        meta: { title: 'Chat', icon: 'MessageSquare', group: 'user' }
      },
      {
        path: 'tenants',
        name: 'Tenants',
        component: TenantView,
        meta: { title: 'Tenants', icon: 'Users', group: 'user' }
      },
      // 管理后台
      {
        path: 'admin',
        name: 'Dashboard',
        component: DashboardView,
        meta: { title: 'Dashboard', icon: 'Activity', group: 'admin' }
      },
      {
        path: 'admin/sessions',
        name: 'Sessions',
        component: SessionsView,
        meta: { title: 'Sessions', icon: 'MessageCircle', group: 'admin' }
      },
      {
        path: 'admin/analytics',
        name: 'Analytics',
        component: AnalyticsView,
        meta: { title: 'Analytics', icon: 'BarChart3', group: 'admin' }
      },
      {
        path: 'admin/logs',
        name: 'Logs',
        component: LogsView,
        meta: { title: 'Logs', icon: 'FileText', group: 'admin' }
      },
      {
        path: 'admin/cron',
        name: 'Cron',
        component: CronView,
        meta: { title: 'Cron Jobs', icon: 'Clock', group: 'admin' }
      },
      {
        path: 'admin/skills',
        name: 'Skills',
        component: SkillsView,
        meta: { title: 'Skills', icon: 'Package', group: 'admin' }
      },
      {
        path: 'admin/config',
        name: 'Config',
        component: ConfigView,
        meta: { title: 'Config', icon: 'Settings', group: 'admin' }
      },
      {
        path: 'admin/env',
        name: 'Environment',
        component: EnvView,
        meta: { title: 'API Keys', icon: 'KeyRound', group: 'admin' }
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router
