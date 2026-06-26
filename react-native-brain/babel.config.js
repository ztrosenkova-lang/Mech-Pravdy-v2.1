module.exports = {
  presets: ['module:@react-native/babel-preset'],
  plugins: [
    // Этот плагин принудительно заставляет Babel использовать внешние хелперы,
    // что полностью блокирует вызов дефолтного хаба и убирает ошибку "Helpers are not supported"!
    ['@babel/plugin-transform-runtime', { helpers: true }]
  ]
};
