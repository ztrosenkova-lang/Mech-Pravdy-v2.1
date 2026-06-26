const {getDefaultConfig, mergeConfig} = require('@react-native/metro-config');
const path = require('path');

const config = {
  resolver: {
    extraNodeModules: {
      '@babel/traverse': path.resolve(__dirname, 'node_modules/@babel/traverse'),
      '@babel/core': path.resolve(__dirname, 'node_modules/@babel/core'),
    },
    blockList: [
      /node_modules\/@pocketpalai\/llama\.rn\/node_modules\/@babel\/.*/
    ]
  },
};

module.exports = mergeConfig(getDefaultConfig(__dirname), config);
