import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { AuthProvider } from './auth/AuthContext';
import { ProtectedRoute } from './auth/ProtectedRoute';
import { ThemeProvider } from './ui/theme';
import { ToastProvider } from './ui/toast';
import { LoginPage } from './pages/LoginPage';
import { SetPasswordPage } from './pages/SetPasswordPage';
import { DashboardPage } from './pages/DashboardPage';
import { TableroPage } from './pages/TableroPage';
import { DevicesPage } from './pages/DevicesPage';
import { DeviceDetailPage } from './pages/DeviceDetailPage';
import { AppsPage } from './pages/AppsPage';
import { ConfigurationsPage } from './pages/ConfigurationsPage';
import { EnrollPage } from './pages/EnrollPage';
import { UsersPage } from './pages/UsersPage';
import { RolesPage } from './pages/RolesPage';
import { AdminOnly } from './auth/AdminOnly';
import { RequirePermission } from './auth/RequirePermission';
import { SettingsPage } from './pages/SettingsPage';
import { FleetMapPage } from './pages/FleetMapPage';
import { GroupsPage } from './pages/GroupsPage';
import { AnnouncementsPage } from './pages/AnnouncementsPage';
import { ReportsPage } from './pages/ReportsPage';
import { AlertsPage } from './pages/AlertsPage';
import { ScriptsPage } from './pages/ScriptsPage';
import { GeofencesPage } from './pages/GeofencesPage';
import { AuditPage } from './pages/AuditPage';
import { SupportSessionsPage } from './pages/SupportSessionsPage';

export default function App() {
  return (
    <AuthProvider>
      <ThemeProvider>
      <ToastProvider>
        <BrowserRouter>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route path="/set-password" element={<SetPasswordPage />} />
            <Route element={<ProtectedRoute />}>
              <Route path="/dashboard" element={<DashboardPage />} />
              <Route path="/tablero" element={<TableroPage />} />
              <Route path="/devices" element={<DevicesPage />} />
              <Route path="/devices/:id" element={<DeviceDetailPage />} />
              <Route path="/map" element={<FleetMapPage />} />
              <Route path="/groups" element={<GroupsPage />} />
              <Route path="/apps" element={<RequirePermission name="applications"><AppsPage /></RequirePermission>} />
              <Route path="/configs" element={<RequirePermission name="configurations"><ConfigurationsPage /></RequirePermission>} />
              <Route path="/announcements" element={<AnnouncementsPage />} />
              <Route path="/reports" element={<RequirePermission name="manage_fleet"><ReportsPage /></RequirePermission>} />
              <Route path="/alerts" element={<RequirePermission name="manage_fleet"><AlertsPage /></RequirePermission>} />
              <Route path="/scripts" element={<RequirePermission name="manage_scripts"><ScriptsPage /></RequirePermission>} />
              <Route path="/enroll" element={<RequirePermission name="enroll_devices"><EnrollPage /></RequirePermission>} />
              <Route path="/settings" element={<AdminOnly><RequirePermission name="settings"><SettingsPage /></RequirePermission></AdminOnly>} />
              <Route path="/users" element={<RequirePermission name="manage_users"><UsersPage /></RequirePermission>} />
              <Route path="/roles" element={<AdminOnly><RequirePermission name="settings"><RolesPage /></RequirePermission></AdminOnly>} />
              <Route path="/geofences" element={<RequirePermission name="manage_fleet"><GeofencesPage /></RequirePermission>} />
              <Route path="/audit" element={<RequirePermission name="view_audit"><AuditPage /></RequirePermission>} />
              <Route path="/soporte" element={<SupportSessionsPage />} />
            </Route>
            <Route path="*" element={<Navigate to="/dashboard" replace />} />
          </Routes>
        </BrowserRouter>
      </ToastProvider>
      </ThemeProvider>
    </AuthProvider>
  );
}
