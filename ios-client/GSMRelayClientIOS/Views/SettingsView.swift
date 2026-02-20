import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var vm: AppViewModel

    @State private var baseURL = ""
    @State private var username = ""
    @State private var password = ""
    @State private var gatewayId = ""
    @State private var masterPhone = ""

    var body: some View {
        Form {
            Section("Server") {
                TextField("Base URL", text: $baseURL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                TextField("Username", text: $username)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                SecureField("Password", text: $password)
                TextField("Gateway ID", text: $gatewayId)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                TextField("Master phone", text: $masterPhone)
                    .keyboardType(.phonePad)
            }

            Section {
                Button("Save settings") {
                    vm.saveConfig(
                        ServerConfig(
                            baseURL: baseURL,
                            username: username,
                            password: password,
                            gatewayId: gatewayId,
                            masterPhone: masterPhone
                        )
                    )
                }

                Button("Restore defaults") {
                    vm.resetConfigToDefaults()
                    bindFromViewModel()
                }

                Button("Sync now") {
                    Task { await vm.refreshAll() }
                }
            }
        }
        .navigationTitle("Settings")
        .onAppear {
            bindFromViewModel()
        }
    }

    private func bindFromViewModel() {
        baseURL = vm.config.baseURL
        username = vm.config.username
        password = vm.config.password
        gatewayId = vm.config.gatewayId
        masterPhone = vm.config.masterPhone
    }
}
