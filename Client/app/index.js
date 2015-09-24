'use strict';

import angular from 'angular'

import './protobufs/protobufs.module'
import './routes/routes.module'
import './directives/directives.module'
import './services/services.module'

//import templateCache from '../templates'

var app = angular.module('client', [
  'template-cache',
  'client.protobufs',
  'client.directives',
  'client.services',
  'client.routes'
]);