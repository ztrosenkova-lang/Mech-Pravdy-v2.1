module.exports = {
  // Указываем точный путь к ядру React Native
  reactNativePath: './react-native-brain/node_modules/react-native',
  project: {
    ios: {},
    android: {
      // sourceDir указывает на корень Gradle проекта (где лежит settings.gradle)
      sourceDir: '.',
      // appName сообщает CLI, что папка с Android-приложением называется 'app'
      appName: 'app',
    },
  },
  assets: ['./app/src/main/assets/'],
};
